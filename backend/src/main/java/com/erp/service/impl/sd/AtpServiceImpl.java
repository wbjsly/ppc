package com.erp.service.impl.sd;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.sd.AtpFactsDao;
import com.erp.dao.sd.AtpTrialDao;
import com.erp.dao.sd.SoChangeDao;
import com.erp.dao.sd.SoDao;
import com.erp.dao.sd.SoLineDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.sd.AtpTrial;
import com.erp.entity.sd.So;
import com.erp.entity.sd.SoChange;
import com.erp.entity.sd.SoLine;
import com.erp.service.SysParamService;
import com.erp.service.sd.AtpService;
import com.erp.service.sd.ReservationService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ATP 承诺与分批交付实现（tasks 8.2~8.5，spec sales-atp-reservation）。
 *
 * 口径（D7 + spec 逐条）：
 *  - 四因子实时算：OnHand(AVAILABLE_QTY 排除待检) + InProcess(恒0标注) + Incoming(已批PO,交期内)
 *    − Reserved(预留表SUM ACTIVE) − SafetyStock(max(物料字段, 日均×SAFETY_DAYS))；
 *  - 计划延迟剔除（BR-4.3-20）：到货晚于客户期望交期的在途不计入并标记；
 *  - 不足仅 L4 提示（BR-4.3-22）：给可承诺量与最早可承诺日期，不削量；
 *  - 拆行守恒（BR-4.3-23）：Σ = 原行，不等 422；已确认单拆行须重锁批次（锁不足整体回滚）。
 */
@Slf4j
@Service
public class AtpServiceImpl implements AtpService {

    /** 生产域未接入的固定标注（spec 场景要求页面常驻） */
    public static final String IN_PROCESS_NOTE = "生产域未接入，在制供给不计入";
    private static final int AVG_WINDOW_DAYS = 90;

    private final AtpFactsDao factsDao;
    private final AtpTrialDao trialDao;
    private final MdmItemDao itemDao;
    private final SoDao soDao;
    private final SoLineDao soLineDao;
    private final SoChangeDao soChangeDao;
    private final ReservationService reservationService;
    private final SysParamService paramService;

    public AtpServiceImpl(AtpFactsDao factsDao,
                          AtpTrialDao trialDao,
                          MdmItemDao itemDao,
                          SoDao soDao,
                          SoLineDao soLineDao,
                          SoChangeDao soChangeDao,
                          ReservationService reservationService,
                          SysParamService paramService) {
        this.factsDao = factsDao;
        this.trialDao = trialDao;
        this.itemDao = itemDao;
        this.soDao = soDao;
        this.soLineDao = soLineDao;
        this.soChangeDao = soChangeDao;
        this.reservationService = reservationService;
        this.paramService = paramService;
    }

    // ---------- 8.2/8.3/8.4 承诺试算 ----------

    @Override
    public Map<String, Object> trial(String itemCode, String warehouseCode,
                                     BigDecimal qty, LocalDate expectDate) {
        if (isBlank(itemCode)) {
            throw new ServiceException(422, "SKU 必填");
        }
        if (qty == null || qty.signum() <= 0) {
            throw new ServiceException(422, "需求量须大于 0");
        }
        String wh = isBlank(warehouseCode) ? "WH-MAIN" : warehouseCode;
        MdmItem item = itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                .eq(MdmItem::getItemCode, itemCode).last("LIMIT 1"));
        if (item == null) {
            throw new ServiceException(422, "物料不存在：" + itemCode);
        }

        // ---- OnHand + 待检排除 ----
        Map<String, Object> stock = factsDao.stockFact(itemCode, wh);
        BigDecimal onHand = num(stock, "A", "a");
        BigDecimal excludedQc = num(stock, "Q", "q");

        // ---- 寄售排除（单列展示） ----
        BigDecimal excludedVmi = nvl(factsDao.vmiRemain(itemCode));

        // ---- Incoming：已批 PO 未收，按期望交期分组（BR-4.3-20 延迟剔除） ----
        List<Map<String, Object>> rows = factsDao.incomingRows(itemCode);
        BigDecimal incoming = BigDecimal.ZERO;
        BigDecimal incomingDelayed = BigDecimal.ZERO;
        List<Map<String, Object>> arrivals = new ArrayList<>();   // 全部确定在途（最早承诺计算）
        for (Map<String, Object> r : rows) {
            LocalDate arrive = toDate(r.get("arrive"));
            BigDecimal open = num(r, "openQty", "OPENQTY", "openqty");
            if (open == null || open.signum() <= 0) {
                continue;
            }
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("arrive", arrive == null ? null : arrive.toString());
            a.put("qty", open);
            if (arrive != null) {
                arrivals.add(a);   // 到货日缺失 = 不确定补货，不进承诺累加（158 行 parse 防 NPE）
            }
            if (arrive == null) {
                // 到货日期未确认 = 不确定补货，不纳入 ATP（spec 分批交付「不确定的补货不得纳入」）
                incomingDelayed = incomingDelayed.add(open);
            } else if (expectDate != null && arrive.isAfter(expectDate)) {
                incomingDelayed = incomingDelayed.add(open);      // 计划延迟，不计入（BR-4.3-20）
            } else {
                incoming = incoming.add(open);
            }
        }

        // ---- Reserved = 预留表 SUM(ACTIVE) ----
        BigDecimal reserved = nvl(reservationService.sumActive(itemCode, wh));

        // ---- SafetyStock = max(物料字段, 日均销量 × SAFETY_DAYS)（BR-4.3-21） ----
        BigDecimal safetyField = item.getSafetyStock() == null ? BigDecimal.ZERO : item.getSafetyStock();
        int safetyDays = paramService.getInt("SAFETY_DAYS", 30);
        BigDecimal issued90 = nvl(factsDao.issuedLast90(itemCode));
        BigDecimal avgDaily = issued90.divide(new BigDecimal(AVG_WINDOW_DAYS), 4, RoundingMode.HALF_UP);
        BigDecimal safetyByFlow = avgDaily.multiply(new BigDecimal(safetyDays));
        BigDecimal safety = safetyField.max(safetyByFlow);

        // ---- ATP ----
        BigDecimal available = onHand.add(incoming).subtract(reserved).subtract(safety);
        boolean enough = available.compareTo(qty) >= 0;

        // ---- 最早可承诺日期 ----
        LocalDate today = LocalDate.now();
        LocalDate earliest = null;
        String promiseNote;
        if (enough) {
            earliest = today;
            promiseNote = "可承诺：现有 ATP 已满足需求（最早今日可承诺）";
        } else {
            BigDecimal cum = available;
            for (Map<String, Object> a : arrivals) {     // 确定在途按到货日累加（含延迟批次作补货承诺）
                Object arriveRaw = a.get("arrive");
                if (arriveRaw == null) {
                    continue;   // 到货日缺失行不参与承诺（防御，正常已在入口过滤）
                }
                cum = cum.add((BigDecimal) a.get("qty"));
                if (cum.compareTo(qty) >= 0) {
                    earliest = LocalDate.parse(String.valueOf(arriveRaw));
                    break;
                }
            }
            promiseNote = earliest != null
                    ? "补货承诺：累计至 " + earliest + " 可满足（仅计已批 PO，不确定补货不纳入）"
                    : "无确定补货计划覆盖需求，需下采购（不确定补货不纳入 ATP）";
        }

        // ---- L4 提示（不削量，C-4.3-02） ----
        List<String> warnings = new ArrayList<>();
        if (!enough) {
            warnings.add("ATP 不足：可承诺 " + strip(available)
                    + "（最早可承诺 " + (earliest == null ? "无确定日期" : earliest.toString())
                    + "），需求 " + strip(qty)
                    + "。行数量保持不变（BR-4.3-22 不自动削量），可选：接受部分交付 / 调整交期 / 分批交付（3.4.2）");
        }
        if (incomingDelayed.signum() > 0) {
            warnings.add("计划延迟/到货日未确认，不计入 ATP：在途 " + strip(incomingDelayed)
                    + (expectDate == null ? "（到货日期未确认，不确定补货不纳入）"
                    : "（晚于期望交期 " + expectDate + "，BR-4.3-20）"));
        }

        Map<String, Object> factors = new LinkedHashMap<>();
        factors.put("onHand", onHand);
        factors.put("excludedQc", excludedQc);
        factors.put("excludedVmi", excludedVmi);
        factors.put("inProcess", BigDecimal.ZERO);
        factors.put("inProcessNote", IN_PROCESS_NOTE);
        factors.put("incoming", incoming);
        factors.put("incomingDelayed", incomingDelayed);
        factors.put("incomingDetail", arrivals);
        factors.put("reserved", reserved);
        factors.put("safetyStock", safety);
        factors.put("safetyField", safetyField);
        factors.put("safetyFlow", safetyByFlow);
        factors.put("safetyDays", safetyDays);
        factors.put("avgDaily", avgDaily);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemCode", itemCode);
        out.put("itemName", item.getItemName());
        out.put("warehouseCode", wh);
        out.put("qty", qty);
        out.put("expectDate", expectDate == null ? null : expectDate.toString());
        out.put("factors", factors);
        out.put("available", available);
        out.put("enough", enough);
        out.put("shortage", enough ? BigDecimal.ZERO : qty.subtract(available).max(BigDecimal.ZERO));
        out.put("earliestPromiseDate", earliest == null ? null : earliest.toString());
        out.put("promiseNote", promiseNote);
        out.put("warnings", warnings);
        // C-4.3-02 两级控制：承诺阶段 L4（本方法），确认阶段 L1（SoConfirmSupport 锁批硬卡）
        out.put("level", enough ? "OK" : "L4");
        return out;
    }

    @Override
    @Transactional
    public AtpTrial saveTrial(Map<String, Object> trialResult, String remark) {
        @SuppressWarnings("unchecked")
        Map<String, Object> f = (Map<String, Object>) trialResult.get("factors");
        if (f == null) {
            throw new ServiceException(422, "试算结果缺失，无法保存");
        }
        AtpTrial t = new AtpTrial();
        t.setId(uuid());
        t.setItemCode(str(trialResult.get("itemCode")));
        t.setItemName(str(trialResult.get("itemName")));
        t.setWarehouseCode(str(trialResult.get("warehouseCode")));
        t.setQty(num(trialResult, "qty"));
        String expect = str(trialResult.get("expectDate"));
        t.setExpectDate(isBlank(expect) ? null : LocalDate.parse(expect));
        t.setOnHand(num(f, "onHand"));
        t.setExcludedQc(num(f, "excludedQc"));
        t.setExcludedVmi(num(f, "excludedVmi"));
        t.setInProcess(BigDecimal.ZERO);
        t.setIncoming(num(f, "incoming"));
        t.setIncomingDelayed(num(f, "incomingDelayed"));
        t.setReserved(num(f, "reserved"));
        t.setSafetyStock(num(f, "safetyStock"));
        t.setAvailable(num(trialResult, "available"));
        t.setEnough(Boolean.TRUE.equals(trialResult.get("enough")) ? "1" : "0");
        String ep = str(trialResult.get("earliestPromiseDate"));
        t.setEarliestPromiseDate(isBlank(ep) ? null : LocalDate.parse(ep));
        t.setStatus(AtpTrial.ST_SAVED);
        t.setRemark(remark);
        trialDao.insert(t);
        log.info("ATP trial saved: {} {} -> {}", t.getItemCode(), t.getAvailable(), t.getEarliestPromiseDate());
        return t;
    }

    @Override
    public Page<AtpTrial> trialPage(long current, long size, String itemCode, String status) {
        return trialDao.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<AtpTrial>()
                        .eq(isNotBlank(itemCode), AtpTrial::getItemCode, itemCode)
                        .eq(isNotBlank(status), AtpTrial::getStatus, status)
                        .orderByDesc(AtpTrial::getCreateDate));
    }

    // ---------- 8.5 分批交付拆行 ----------

    @Override
    @Transactional
    public Map<String, Object> splitLine(String lineId, List<Map<String, Object>> batches) {
        SoLine line = soLineDao.selectById(lineId);
        if (line == null) {
            throw new ServiceException(404, "订单行不存在：" + lineId);
        }
        So so = soDao.selectById(line.getSoId());
        if (so == null) {
            throw new ServiceException(404, "订单不存在");
        }
        if (!So.ST_DRAFT.equals(so.getStatus()) && !So.ST_CONFIRMED.equals(so.getStatus())) {
            throw new ServiceException(422, "仅草稿或已确认订单可拆分分批（当前：" + so.getStatus() + "）");
        }
        if (batches == null || batches.size() < 2) {
            throw new ServiceException(422, "分批方案至少两批");
        }
        // ---- 守恒校验（BR-4.3-23：Σ = 原行，不等回滚） ----
        BigDecimal totalBefore = line.getQty();
        BigDecimal totalAfter = BigDecimal.ZERO;
        for (Map<String, Object> b : batches) {
            BigDecimal q = num(b, "qty");
            if (q == null || q.signum() <= 0) {
                throw new ServiceException(422, "每批数量须大于 0");
            }
            totalAfter = totalAfter.add(q);
        }
        if (totalBefore.subtract(totalAfter).abs().compareTo(new BigDecimal("0.001")) > 0) {
            throw new ServiceException(422, "拆分前后数量必须一致：原行 " + strip(totalBefore)
                    + "，方案合计 " + strip(totalAfter) + "（BR-4.3-23 守恒，已回滚）");
        }

        boolean confirmed = So.ST_CONFIRMED.equals(so.getStatus());
        // 已确认：释放原行全部预留（拆分后按新行重锁；失败整体回滚）
        if (confirmed) {
            reservationService.releaseByLine(lineId, null, "分批拆行重锁");
        }

        // ---- 原行改为第一段 ----
        Map<String, Object> first = batches.get(0);
        BigDecimal oldQty = line.getQty();
        line.setQty(num(first, "qty"));
        String firstDate = str(first.get("planShipDate"));
        if (!isBlank(firstDate)) {
            line.setPlanShipDate(LocalDate.parse(firstDate));
        }
        if (!isBlank(str(first.get("warehouseCode")))) {
            line.setWarehouseCode(str(first.get("warehouseCode")));
        }
        if (confirmed) {
            line.setReservedQty(BigDecimal.ZERO);
        }
        soLineDao.updateById(line);

        // ---- 后续批次复制为新行 ----
        int maxNo = line.getLineNo() == null ? 0 : line.getLineNo();
        List<SoLine> allLines = new ArrayList<>();
        allLines.add(line);
        for (int i = 1; i < batches.size(); i++) {
            Map<String, Object> b = batches.get(i);
            SoLine nl = new SoLine();
            nl.setId(uuid());
            nl.setSoId(line.getSoId());
            nl.setLineNo(++maxNo);
            nl.setQuoteLineId(line.getQuoteLineId());
            nl.setItemCode(line.getItemCode());
            nl.setItemName(line.getItemName());
            nl.setBaseUnit(line.getBaseUnit());
            nl.setUnitPrice(line.getUnitPrice());
            nl.setOriginalUnitPrice(line.getOriginalUnitPrice());
            nl.setPriceSource(line.getPriceSource());
            nl.setPaCode(line.getPaCode());
            nl.setAmount(num(b, "qty").multiply(nvl(line.getUnitPrice()))
                    .setScale(2, RoundingMode.HALF_UP));
            nl.setQty(num(b, "qty"));
            String d = str(b.get("planShipDate"));
            nl.setPlanShipDate(isBlank(d) ? null : LocalDate.parse(d));
            nl.setCustomerExpectDate(line.getCustomerExpectDate());
            nl.setWarehouseCode(isBlank(str(b.get("warehouseCode")))
                    ? line.getWarehouseCode() : str(b.get("warehouseCode")));
            nl.setPriceLocked(line.getPriceLocked());
            nl.setPriceApprovalId(line.getPriceApprovalId());
            nl.setLineStatus(line.getLineStatus());
            nl.setShippedQty(BigDecimal.ZERO);
            nl.setInvoicedQty(BigDecimal.ZERO);
            nl.setReservedQty(BigDecimal.ZERO);
            nl.setRemark("分批拆行（原行 " + line.getLineNo() + " 第 " + (i + 1) + " 批）");
            soLineDao.insert(nl);
            allLines.add(nl);
        }

        // ---- 已确认：按新行重新锁批（锁不足 → 422 整体回滚含拆行） ----
        int reservedLines = 0;
        if (confirmed) {
            reservationService.reserveForSo(so, allLines);
            reservedLines = allLines.size();
        }

        // ---- 留痕 + 守恒复核 ----
        recordSplit(so.getId(), line.getLineNo(), strip(totalBefore), strip(totalAfter), batches.size());
        BigDecimal check = allLines.stream().map(SoLine::getQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (check.subtract(totalBefore).abs().compareTo(new BigDecimal("0.001")) > 0) {
            throw new ServiceException(422, "拆分后守恒复核失败，已回滚");
        }
        log.info("SO {} line {} split into {} batches ({} -> {})", so.getSoNo(),
                line.getLineNo(), batches.size(), strip(totalBefore), strip(totalAfter));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("soNo", so.getSoNo());
        out.put("lineNo", line.getLineNo());
        out.put("totalBefore", totalBefore);
        out.put("totalAfter", check);
        out.put("batchCount", batches.size());
        out.put("reservedLines", reservedLines);
        out.put("lines", allLines);
        return out;
    }

    // ---------- 行级 ATP 检查（C-4.3-02 L4 数据） ----------

    @Override
    public List<Map<String, Object>> checkSoLines(String soId) {
        List<SoLine> lines = soLineDao.selectList(new LambdaQueryWrapper<SoLine>()
                .eq(SoLine::getSoId, soId).orderByAsc(SoLine::getLineNo));
        List<Map<String, Object>> out = new ArrayList<>();
        for (SoLine l : lines) {
            if (SoLine.LS_CANCELLED.equals(l.getLineStatus())) {
                continue;
            }
            LocalDate expect = l.getCustomerExpectDate() != null
                    ? l.getCustomerExpectDate() : l.getPlanShipDate();
            Map<String, Object> t = trial(l.getItemCode(), l.getWarehouseCode(), l.getQty(), expect);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lineNo", l.getLineNo());
            row.put("itemCode", l.getItemCode());
            row.put("qty", l.getQty());
            row.put("atp", t.get("available"));
            row.put("enough", t.get("enough"));
            row.put("earliestPromiseDate", t.get("earliestPromiseDate"));
            row.put("warnings", t.get("warnings"));
            row.put("level", t.get("level"));
            out.add(row);
        }
        return out;
    }

    // ---------- helpers ----------

    private void recordSplit(String soId, Integer lineNo, String before, String after, int batches) {
        SoChange c = new SoChange();
        c.setId(uuid());
        c.setSoId(soId);
        c.setLineNo(lineNo);
        c.setFieldName("LINE_SPLIT");
        c.setOldValue(before);
        c.setNewValue(after + " × " + batches + " 批");
        c.setReason("分批交付拆行（BR-4.3-23 守恒校验通过）");
        c.setOperatorId(SecurityUtils.getCurrentUserId() == null ? "system"
                : SecurityUtils.getCurrentUserId());
        c.setOperateAt(LocalDateTime.now());
        soChangeDao.insert(c);
    }

    private static BigDecimal num(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v == null) {
                continue;
            }
            if (v instanceof BigDecimal bd) {
                return bd;
            }
            try {
                return new BigDecimal(String.valueOf(v));
            } catch (NumberFormatException ignore) {
                // 尝试下一个键
            }
        }
        return null;
    }

    private static LocalDate toDate(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDate d) {
            return d;
        }
        String s = String.valueOf(v);
        if (s.length() >= 10) {
            try {
                return LocalDate.parse(s.substring(0, 10));
            } catch (Exception ignore) {
                return null;
            }
        }
        return null;
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String strip(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean isNotBlank(String s) {
        return !isBlank(s);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
