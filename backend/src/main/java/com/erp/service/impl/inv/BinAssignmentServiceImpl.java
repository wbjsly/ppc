package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvBatchDao;
import com.erp.dao.inv.InvBinDao;
import com.erp.dao.inv.InvPutawayDao;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.inv.InvZoneDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.proc.GoodsReceiptDao;
import com.erp.dao.proc.GoodsReceiptLineDao;
import com.erp.entity.inv.InvBatch;
import com.erp.entity.inv.InvBin;
import com.erp.entity.inv.InvPutaway;
import com.erp.entity.inv.InvStock;
import com.erp.entity.inv.InvZone;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.proc.GoodsReceipt;
import com.erp.entity.proc.GoodsReceiptLine;
import com.erp.service.inv.BinAssignmentService;
import com.erp.service.system.NoticeService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 仓位分配实现（4.4.5，spec bin-assignment，design D3/D4/D6）：
 * 推荐器 = 过滤漏斗（类型分流 BR-4.4-09 → 三属性精确相等 C-4.4-12 → 托位容量 BR-4.4-12）
 * + 三级排序（同物料同位 > 效期相近 > 区域就近，Top3 带理由）；
 * 台账状态机 RECOMMENDED → CONFIRMED，改派=新记录 SUPERSEDED_BY 替代（禁原地改）；
 * Tab A 强前置数据源（GR 行）、Tab B 未分配（BIN_CODE=''）补上架（合并物理删源行，design D6）。
 * 无候选 → SUSPENDED 挂起记录 + 通知仓库主管（C-4.4-11）。
 */
@Slf4j
@Service
public class BinAssignmentServiceImpl implements BinAssignmentService {

    private static final String STORE = "STORE";
    private static final List<String> QC_BIN_TYPES = List.of("RETURN", "SCRAP");
    private static final Set<String> ELIGIBLE_QC = Set.of("RELEASED", "SKIPPED", "CONCESSION");
    private static final int TOP_N = 3;

    private final GoodsReceiptLineDao grLineDao;
    private final GoodsReceiptDao grDao;
    private final InvPutawayDao putawayDao;
    private final InvStockDao stockDao;
    private final InvBinDao binDao;
    private final InvZoneDao zoneDao;
    private final InvBatchDao batchDao;
    private final MdmItemDao itemDao;
    private final NoticeService noticeService;

    public BinAssignmentServiceImpl(GoodsReceiptLineDao grLineDao, GoodsReceiptDao grDao,
                                    InvPutawayDao putawayDao, InvStockDao stockDao,
                                    InvBinDao binDao, InvZoneDao zoneDao, InvBatchDao batchDao,
                                    MdmItemDao itemDao, NoticeService noticeService) {
        this.grLineDao = grLineDao;
        this.grDao = grDao;
        this.putawayDao = putawayDao;
        this.stockDao = stockDao;
        this.binDao = binDao;
        this.zoneDao = zoneDao;
        this.batchDao = batchDao;
        this.itemDao = itemDao;
        this.noticeService = noticeService;
    }

    // ================= Tab A：待分配队列 =================

    @Override
    public Map<String, Object> pendingGrLines(String keyword, long current, long size) {
        Page<Map<String, Object>> p = new Page<>(current, size);
        grLineDao.selectAssignPending(p, keyword);
        Set<String> docNos = new java.util.LinkedHashSet<>();
        for (Map<String, Object> row : p.getRecords()) {
            docNos.add(String.valueOf(row.get("GR_NO")));
        }
        Map<String, List<InvPutaway>> byDoc = new HashMap<>();
        if (!docNos.isEmpty()) {
            for (InvPutaway r : putawayDao.selectList(new LambdaQueryWrapper<InvPutaway>()
                    .eq(InvPutaway::getSourceType, InvPutaway.SRC_GR)
                    .in(InvPutaway::getSourceDocNo, docNos))) {
                byDoc.computeIfAbsent(r.getSourceDocNo(), k -> new ArrayList<>()).add(r);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : p.getRecords()) {
            String grNo = String.valueOf(row.get("GR_NO"));
            int lineNo = ((Number) row.get("LINE_NO")).intValue();
            InvPutaway confirmed = null;
            InvPutaway suspended = null;
            InvPutaway recommended = null;
            for (InvPutaway r : byDoc.getOrDefault(grNo, List.of())) {
                if (r.getSourceLineNo() == null || r.getSourceLineNo() != lineNo) {
                    continue;
                }
                if (r.getSupersededBy() != null) {
                    continue;
                }
                if (InvPutaway.ST_CONFIRMED.equals(r.getStatus())) {
                    confirmed = r;
                } else if (InvPutaway.ST_SUSPENDED.equals(r.getStatus())) {
                    suspended = r;
                } else {
                    recommended = r;
                }
            }
            InvPutaway activeRec = confirmed != null ? confirmed
                    : suspended != null ? suspended : recommended;
            String allocStatus = confirmed != null ? InvPutaway.ST_CONFIRMED
                    : suspended != null ? InvPutaway.ST_SUSPENDED
                    : recommended != null ? InvPutaway.ST_RECOMMENDED : "PENDING";
            row.put("allocationStatus", allocStatus);
            row.put("binCode", confirmed != null ? confirmed.getBinCode()
                    : recommended != null ? recommended.getBinCode() : null);
            row.put("confirmedQty", confirmed != null ? confirmed.getQty() : BigDecimal.ZERO);
            row.put("suspended", InvPutaway.ST_SUSPENDED.equals(allocStatus));
            // 当前生效记录 id（前端 确认/重推 操作句柄）
            row.put("recordId", activeRec != null ? activeRec.getId() : null);
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", p.getTotal());
        out.put("asOf", java.time.LocalDateTime.now());
        return out;
    }

    // ================= 建议 / 推荐 / 确认 / 指定 =================

    @Override
    public List<Map<String, Object>> candidates(String grNo, Integer lineNo) {
        EligibleLine line = requireEligible(grNo, lineNo);
        return top3(line);
    }

    @Override
    @Transactional
    public Map<String, Object> recommend(String grNo, Integer lineNo) {
        requireWrite("推荐仓位");
        EligibleLine line = requireEligible(grNo, lineNo);
        List<InvPutaway> active = activeRecords(InvPutaway.SRC_GR, grNo, lineNo);
        InvPutaway confirmed = active.stream()
                .filter(r -> InvPutaway.ST_CONFIRMED.equals(r.getStatus())).findFirst().orElse(null);
        List<Map<String, Object>> cands = top3(line);
        if (confirmed != null) {
            return result(confirmed, cands, false, true);
        }
        if (cands.isEmpty()) {
            InvPutaway suspended = createRecord(InvPutaway.SRC_GR, grNo, lineNo,
                    line.wh, line.itemCode, line.batch, line.qty, "",
                    InvPutaway.ST_SUSPENDED, "无可用仓位（C-4.4-11 挂起）");
            supersedeAll(active, suspended.getId());
            noticeService.push("ROLE_WAREHOUSE", null, "仓位分配挂起",
                    "收货单 " + grNo + " 行 " + lineNo + " 物料 " + line.itemCode
                            + " 批次 " + (line.batch.isEmpty() ? "(无)" : line.batch)
                            + "：推荐后无可用仓位，请检查仓位属性/托位容量或扩仓位后重新推荐",
                    "BIN_ASSIGN", grNo + "#" + lineNo);
            return result(suspended, List.of(), true, false);
        }
        String bin = String.valueOf(cands.get(0).get("binCode"));
        InvPutaway rec = createRecord(InvPutaway.SRC_GR, grNo, lineNo,
                line.wh, line.itemCode, line.batch, line.qty, bin,
                InvPutaway.ST_RECOMMENDED, null);
        supersedeAll(active, rec.getId());
        return result(rec, cands, false, false);
    }

    @Override
    @Transactional
    public Map<String, Object> recommendAll() {
        requireWrite("一键推荐仓位");
        int confirmed = 0;
        int suspended = 0;
        long current = 1;
        while (true) {
            Page<Map<String, Object>> p = new Page<>(current, 500);
            grLineDao.selectAssignPending(p, null);
            if (p.getRecords().isEmpty()) {
                break;
            }
            for (Map<String, Object> row : p.getRecords()) {
                String grNo = String.valueOf(row.get("GR_NO"));
                int lineNo = ((Number) row.get("LINE_NO")).intValue();
                try {
                    Map<String, Object> r = recommend(grNo, lineNo);
                    if (Boolean.TRUE.equals(r.get("suspended"))) {
                        suspended++;
                    } else {
                        confirm(String.valueOf(((Map<?, ?>) r.get("record")).get("id")));
                        confirmed++;
                    }
                } catch (ServiceException e) {
                    if (e.getCode() == 401 || e.getCode() == 403) {
                        throw e;
                    }
                    suspended++;
                    log.warn("一键推荐行异常挂起 {}#{}: {}", grNo, lineNo, e.getMessage());
                }
            }
            if (p.getRecords().size() < 500) {
                break;
            }
            current++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("confirmed", confirmed);
        out.put("suspended", suspended);
        return out;
    }

    @Override
    @Transactional
    public void confirm(String putawayId) {
        requireWrite("确认分配");
        InvPutaway rec = putawayDao.selectById(putawayId);
        if (rec == null) {
            throw new ServiceException(404, "分配记录不存在");
        }
        if (InvPutaway.ST_CONFIRMED.equals(rec.getStatus())) {
            return;   // 幂等
        }
        if (InvPutaway.ST_SUSPENDED.equals(rec.getStatus())) {
            throw new ServiceException(422, "该行处于无可用仓位挂起状态，请重新推荐");
        }
        if (InvPutaway.SRC_STOCK.equals(rec.getSourceType())) {
            executePutaway(rec);
        }
        rec.setStatus(InvPutaway.ST_CONFIRMED);
        rec.setUpdateBy(SecurityUtils.getCurrentUserId());
        putawayDao.updateById(rec);
    }

    @Override
    @Transactional
    public void assign(String grNo, Integer lineNo, String binCode) {
        requireWrite("指定/改派仓位");
        if (binCode == null || binCode.trim().isEmpty()) {
            throw new ServiceException(422, "目标仓位必填");
        }
        EligibleLine line = requireEligible(grNo, lineNo);
        // 人工指定不豁免合规与容量校验（spec 场景：违规 422 并提示合法清单）
        List<Map<String, Object>> legal = recommendBins(line.wh, line.itemCode,
                line.batch, line.expiry, line.qcLocked, true);
        boolean ok = legal.stream().anyMatch(c -> binCode.equals(c.get("binCode")));
        if (!ok) {
            List<String> names = legal.stream().limit(5)
                    .map(c -> String.valueOf(c.get("binCode"))).toList();
            throw new ServiceException(422, "目标仓位不满足合规/容量要求（C-4.4-12 / BR-4.4-12），"
                    + "合法仓位：" + (names.isEmpty() ? "无" : String.join("、", names)));
        }
        // 先取既有生效记录再插入新记录（否则新记录会被自己"替代"，自陷失效）
        List<InvPutaway> active = activeRecords(InvPutaway.SRC_GR, grNo, lineNo);
        InvPutaway rec = createRecord(InvPutaway.SRC_GR, grNo, lineNo,
                line.wh, line.itemCode, line.batch, line.qty, binCode.trim(),
                InvPutaway.ST_CONFIRMED, null);
        supersedeAll(active, rec.getId());
    }

    // ================= Tab B：未分配补上架 =================

    @Override
    public Map<String, Object> unassignedStock(String keyword, long current, long size) {
        long offset = Math.max(0, (current - 1) * size);
        List<InvStock> list = putawayDao.pageUnassigned(keyword, offset, size);
        // 附当前生效上架记录（RECOMMENDED → 前端据此出「确认上架」按钮）
        Map<String, InvPutaway> activePutaway = new HashMap<>();
        if (!list.isEmpty()) {
            List<String> ids = new ArrayList<>();
            for (InvStock s : list) {
                ids.add(s.getId());
            }
            for (InvPutaway r : putawayDao.selectList(new LambdaQueryWrapper<InvPutaway>()
                    .eq(InvPutaway::getSourceType, InvPutaway.SRC_STOCK)
                    .in(InvPutaway::getSourceDocNo, ids)
                    .isNull(InvPutaway::getSupersededBy))) {
                activePutaway.put(r.getSourceDocNo(), r);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (InvStock s : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("warehouseCode", s.getWarehouseCode());
            m.put("itemCode", s.getItemCode());
            m.put("itemName", s.getItemName());
            m.put("batchNo", s.getBatchNo());
            m.put("qty", s.getQty());
            m.put("qcQty", s.getQcQty());
            m.put("availableQty", s.getAvailableQty());
            m.put("inboundDate", s.getInboundDate());
            m.put("createDate", s.getCreateDate());
            InvPutaway rec = activePutaway.get(s.getId());
            m.put("allocationStatus", rec == null ? "PENDING" : rec.getStatus());
            m.put("binCode", rec == null ? null : rec.getBinCode());
            m.put("recordId", rec == null ? null : rec.getId());
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", putawayDao.countUnassigned(keyword));
        out.put("asOf", java.time.LocalDateTime.now());
        return out;
    }

    @Override
    public List<Map<String, Object>> unassignedCandidates(String stockId) {
        return top3ForStock(stockId);
    }

    @Override
    @Transactional
    public Map<String, Object> recommendPutaway(String stockId) {
        requireWrite("推荐上架");
        InvStock row = requireUnassigned(stockId);
        List<InvPutaway> active = activeRecords(InvPutaway.SRC_STOCK, stockId, 0);
        InvPutaway confirmed = active.stream()
                .filter(r -> InvPutaway.ST_CONFIRMED.equals(r.getStatus())).findFirst().orElse(null);
        List<Map<String, Object>> cands = top3ForStock(stockId);
        if (confirmed != null) {
            return result(confirmed, cands, false, true);
        }
        if (cands.isEmpty()) {
            InvPutaway suspended = createRecord(InvPutaway.SRC_STOCK, stockId, 0,
                    row.getWarehouseCode(), row.getItemCode(), row.getBatchNo(),
                    row.getQty(), "", InvPutaway.ST_SUSPENDED, "无可用仓位（C-4.4-11 挂起）");
            supersedeAll(active, suspended.getId());
            noticeService.push("ROLE_WAREHOUSE", null, "上架挂起",
                    "库存行 " + row.getItemCode() + " 批次 " + row.getBatchNo()
                            + "（未分配位）：推荐后无可用仓位，请检查仓位属性/托位容量",
                    "BIN_ASSIGN", "STOCK#" + stockId);
            return result(suspended, List.of(), true, false);
        }
        String bin = String.valueOf(cands.get(0).get("binCode"));
        InvPutaway rec = createRecord(InvPutaway.SRC_STOCK, stockId, 0,
                row.getWarehouseCode(), row.getItemCode(), row.getBatchNo(),
                row.getQty(), bin, InvPutaway.ST_RECOMMENDED, null);
        supersedeAll(active, rec.getId());
        return result(rec, cands, false, false);
    }

    @Override
    public List<Map<String, Object>> history(String sourceDocNo) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (InvPutaway r : putawayDao.selectList(new LambdaQueryWrapper<InvPutaway>()
                .eq(InvPutaway::getSourceDocNo, sourceDocNo)
                .orderByAsc(InvPutaway::getCreateDate))) {
            out.add(toMap(r));
        }
        return out;
    }

    // ================= 上架执行（design D6：同行合并物理删源行） =================

    private void executePutaway(InvPutaway rec) {
        InvStock source = stockDao.selectOne(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getId, rec.getSourceDocNo())
                .last("FOR UPDATE"));
        if (source == null) {
            throw new ServiceException(404, "库存行不存在");
        }
        if (!"".equals(str(source.getBinCode()))) {
            throw new ServiceException(422, "该库存行已分配仓位，无需重复上架");
        }
        // 锁序 (WH,ITEM,BATCH,BIN)：源行 '' < 目标位，先源后目标
        InvStock target = stockDao.selectOne(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getWarehouseCode, source.getWarehouseCode())
                .eq(InvStock::getItemCode, source.getItemCode())
                .eq(InvStock::getBatchNo, str(source.getBatchNo()))
                .eq(InvStock::getBinCode, rec.getBinCode())
                .last("FOR UPDATE"));
        if (target == null) {
            // 无同行：仅移位（唯一键由 '' 变为目标位，无冲突）
            stockDao.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InvStock>()
                    .eq(InvStock::getId, source.getId())
                    .set(InvStock::getBinCode, rec.getBinCode()));
        } else {
            // 同行合并：数量并入目标行 + 物理删除源行（软删占键坑，design D6）
            int merged = stockDao.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InvStock>()
                            .eq(InvStock::getId, target.getId())
                            .setSql("QTY = QTY + " + strip(nvl(source.getQty())))
                            .setSql("AVAILABLE_QTY = AVAILABLE_QTY + " + strip(nvl(source.getAvailableQty())))
                            .setSql("QC_QTY = QC_QTY + " + strip(nvl(source.getQcQty())))
                            .setSql("FIN_QTY = FIN_QTY + " + strip(nvl(source.getFinQty()))));
            if (merged == 0) {
                throw new ServiceException(409, "上架合并并发冲突，请重试");
            }
            stockDao.physicalDelete(source.getId());
        }
    }

    // ================= 推荐器 =================

    private List<Map<String, Object>> top3(EligibleLine line) {
        return new ArrayList<>(recommendBins(line.wh, line.itemCode, line.batch,
                line.expiry, line.qcLocked, true).stream().limit(TOP_N).toList());
    }

    private List<Map<String, Object>> top3ForStock(String stockId) {
        InvStock row = requireUnassigned(stockId);
        MdmItem item = itemOf(row.getItemCode());
        boolean qcLocked = nvl(row.getAvailableQty()).signum() <= 0
                && nvl(row.getQcQty()).signum() > 0;
        return new ArrayList<>(recommendBins(row.getWarehouseCode(), row.getItemCode(),
                str(row.getBatchNo()), expiryOf(row.getItemCode(), str(row.getBatchNo())),
                qcLocked, true).stream().limit(TOP_N).toList());
    }

    /**
     * 过滤漏斗 + 三级排序（design D3）。
     * ① 类型分流：正常行 binType=存储区（空=未分级按存储区放行），QC 锁定行仅退货/残次区（严格匹配）；
     * ② 合规：物料三属性非空时须与仓位等级精确相等，仓位等级空=未分级放行（双向宽松取交集）；
     * ③ 容量：有货批次数 + 本次新占托位 ≤ CAPACITY_PALLET（NULL 不限；同批次已在该位补货占 0 托）。
     * 排序：同物料同位 → 效期差（仅同物料仓位计）→ 区域 SORT_ORDER → 仓位编号字典序。
     */
    private List<Map<String, Object>> recommendBins(String wh, String itemCode, String batch,
                                                    LocalDate expiry, boolean qcLocked,
                                                    boolean includeAll) {
        MdmItem item = itemOf(itemCode);
        List<InvZone> zones = zoneDao.selectList(new LambdaQueryWrapper<InvZone>()
                .eq(InvZone::getWhCode, wh)
                .eq(InvZone::getStatus, "1"));
        Map<String, InvZone> zoneByCode = new HashMap<>();
        for (InvZone z : zones) {
            zoneByCode.put(z.getZoneCode(), z);
        }
        List<InvBin> bins = binDao.selectList(new LambdaQueryWrapper<InvBin>()
                .eq(InvBin::getWhCode, wh)
                .eq(InvBin::getStatus, "1"));

        // 占位统计（有货批次数）+ 该物料既有位（同物料优先）+ 目标批次已在各位（补货占 0 托）
        Map<String, Integer> occupied = new HashMap<>();
        Map<String, LocalDate> itemExpiryByBin = new HashMap<>();
        java.util.Set<String> itemBins = new java.util.HashSet<>();
        java.util.Set<String> targetRowBins = new java.util.HashSet<>();
        for (InvStock s : stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getWarehouseCode, wh)
                .ne(InvStock::getBinCode, ""))) {
            if (nvl(s.getQty()).signum() > 0) {
                occupied.merge(str(s.getBinCode()), 1, Integer::sum);
            }
            if (itemCode != null && itemCode.equals(s.getItemCode())) {
                String b = str(s.getBinCode());
                itemBins.add(b);
                LocalDate e = expiryOf(s.getItemCode(), str(s.getBatchNo()));
                if (e != null) {
                    LocalDate cur = itemExpiryByBin.get(b);
                    if (cur == null || e.isBefore(cur)) {
                        itemExpiryByBin.put(b, e);
                    }
                }
                if (batch != null && batch.equals(str(s.getBatchNo()))) {
                    targetRowBins.add(b);
                }
            }
        }

        List<Map<String, Object>> legal = new ArrayList<>();
        for (InvBin bin : bins) {
            InvZone zone = zoneByCode.get(bin.getZoneCode());
            if (zone == null) {
                continue;
            }
            // ① 类型分流（BR-4.4-09）
            String type = bin.getBinType();
            if (qcLocked) {
                if (!QC_BIN_TYPES.contains(type)) {
                    continue;
                }
            } else if (type != null && !type.isEmpty() && !STORE.equals(type)) {
                continue;
            }
            // ② 合规（C-4.4-12，精确相等；物料 NULL 跳过、仓位 NULL 未分级放行）
            if (!compliant(item, bin)) {
                continue;
            }
            // ③ 容量（BR-4.4-12）
            boolean needNewRow = !targetRowBins.contains(bin.getBinCode());
            int used = occupied.getOrDefault(bin.getBinCode(), 0);
            int need = needNewRow ? 1 : 0;
            Integer cap = bin.getCapacityPallet();
            if (cap != null && used + need > cap) {
                continue;
            }
            boolean sameItem = itemBins.contains(bin.getBinCode());
            Integer expiryDiff = null;
            if (sameItem && expiry != null) {
                LocalDate stored = itemExpiryByBin.get(bin.getBinCode());
                if (stored != null) {
                    expiryDiff = (int) Math.abs(ChronoUnit.DAYS.between(expiry, stored));
                }
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("binCode", bin.getBinCode());
            c.put("zoneName", zone.getZoneName());
            c.put("zoneSort", zone.getSortOrder());
            c.put("binType", type);
            c.put("sameItem", sameItem);
            c.put("expiryDiffDays", expiryDiff);
            c.put("capacityLeft", cap == null ? null : cap - used - need);
            c.put("needNewRow", needNewRow);
            c.put("qcLocked", qcLocked);
            c.put("reasons", reasons(sameItem, expiryDiff, zone.getZoneName(), expiry));
            legal.add(c);
        }
        legal.sort(Comparator
                .comparing((Map<String, Object> c) -> Boolean.FALSE.equals(c.get("sameItem")) ? 1 : 0)
                .thenComparingInt(c -> {
                    Object d = c.get("expiryDiffDays");
                    return d == null ? Integer.MAX_VALUE : (Integer) d;
                })
                .thenComparingInt(c -> c.get("zoneSort") == null ? Integer.MAX_VALUE : (Integer) c.get("zoneSort"))
                .thenComparing(c -> String.valueOf(c.get("binCode"))));
        return legal;
    }

    private List<String> reasons(boolean sameItem, Integer expiryDiff, String zoneName,
                                 LocalDate expiry) {
        List<String> out = new ArrayList<>();
        if (sameItem) {
            out.add("同物料同仓位（集并入库）");
            if (expiryDiff != null) {
                out.add("效期相近（相差 " + expiryDiff + " 天）");
            }
        } else {
            out.add("区域就近（" + zoneName + "）");
        }
        return out;
    }

    private boolean compliant(MdmItem item, InvBin bin) {
        if (item == null) {
            return true;
        }
        return match(item.getTempLevel(), bin.getTempLevel())
                && match(item.getHazardLevel(), bin.getHazardLevel())
                && match(item.getCleanLevel(), bin.getCleanLevel());
    }

    /** 精确相等：物料空=无要求放行；仓位空=未分级放行；两端非空必须相等（偏差 D6） */
    private boolean match(String itemVal, String binVal) {
        if (itemVal == null || itemVal.trim().isEmpty()) {
            return true;
        }
        if (binVal == null || binVal.trim().isEmpty()) {
            return true;
        }
        return itemVal.equals(binVal);
    }

    // ================= 行资格 / 记录工具 =================

    private static final class EligibleLine {
        String wh;
        String grNo;
        int lineNo;
        String itemCode;
        String batch;
        BigDecimal qty;
        boolean qcLocked;
        LocalDate expiry;
    }

    /** 队列资格 = 过账前置同口径（spec bin-assignment 待分配队列） */
    private EligibleLine requireEligible(String grNo, Integer lineNo) {
        if (grNo == null || lineNo == null) {
            throw new ServiceException(422, "单号与行号必填");
        }
        GoodsReceipt gr = grDao.selectOne(new LambdaQueryWrapper<GoodsReceipt>()
                .eq(GoodsReceipt::getGrNo, grNo));
        if (gr == null) {
            throw new ServiceException(404, "收货单不存在：" + grNo);
        }
        if (!"CREATED".equals(gr.getStatus())) {
            throw new ServiceException(422, "仅待过账（CREATED）收货单可分配仓位");
        }
        GoodsReceiptLine line = grLineDao.selectOne(new LambdaQueryWrapper<GoodsReceiptLine>()
                .eq(GoodsReceiptLine::getGrId, gr.getId())
                .eq(GoodsReceiptLine::getLineNo, lineNo));
        if (line == null) {
            throw new ServiceException(404, "收货行不存在：" + grNo + " 行 " + lineNo);
        }
        if (!"PENDING".equals(line.getStatus())) {
            throw new ServiceException(422, "该行非待过账状态");
        }
        BigDecimal qty = nvl(line.getWithinToleranceQty());
        if (qty.signum() <= 0) {
            throw new ServiceException(422, "该行无核销量，无需分配");
        }
        String qc = line.getQcStatus();
        if (!ELIGIBLE_QC.contains(qc)) {
            throw new ServiceException(422, "检验未放行（当前：" + qc + "），暂不可分配仓位");
        }
        EligibleLine out = new EligibleLine();
        out.wh = InvStock.DEFAULT_WH;   // GR 无仓库维度，与过账一致落默认仓
        out.grNo = grNo;
        out.lineNo = lineNo;
        out.itemCode = line.getItemCode();
        out.batch = str(gr.getBatchNo());
        out.qty = qty;
        out.qcLocked = "CONCESSION".equals(qc);   // 让步锁定量入受限区（spec 类型分流）
        out.expiry = expiryOf(out.itemCode, out.batch);
        return out;
    }

    private InvStock requireUnassigned(String stockId) {
        InvStock row = stockDao.selectOne(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getId, stockId)
                .last("FOR UPDATE"));
        if (row == null) {
            throw new ServiceException(404, "库存行不存在");
        }
        if (!"".equals(str(row.getBinCode()))) {
            throw new ServiceException(422, "该库存行已分配仓位");
        }
        return row;
    }

    private List<InvPutaway> activeRecords(String sourceType, String docNo, Integer lineNo) {
        LambdaQueryWrapper<InvPutaway> qw = new LambdaQueryWrapper<InvPutaway>()
                .eq(InvPutaway::getSourceType, sourceType)
                .eq(InvPutaway::getSourceDocNo, docNo)
                .isNull(InvPutaway::getSupersededBy);
        if (lineNo != null) {
            qw.eq(InvPutaway::getSourceLineNo, lineNo);
        }
        return putawayDao.selectList(qw);
    }

    /** 改派留痕：旧生效记录 SUPERSEDED_BY = 新记录（null=仅清生效位，如挂起覆盖推荐） */
    private void supersedeAll(List<InvPutaway> active, String newId) {
        for (InvPutaway old : active) {
            old.setSupersededBy(newId != null ? newId : "superseded:" + java.util.UUID.randomUUID()
                    .toString().substring(0, 8));
            old.setUpdateBy(SecurityUtils.getCurrentUserId());
            putawayDao.updateById(old);
        }
    }

    private InvPutaway createRecord(String sourceType, String docNo, int lineNo, String wh,
                                    String itemCode, String batch, BigDecimal qty, String binCode,
                                    String status, String remark) {
        InvPutaway r = new InvPutaway();
        r.setSourceType(sourceType);
        r.setSourceDocNo(docNo);
        r.setSourceLineNo(lineNo);
        r.setWarehouseCode(wh);
        r.setItemCode(itemCode);
        r.setBatchNo(batch == null ? "" : batch);
        r.setQty(qty);
        r.setBinCode(binCode);
        r.setStatus(status);
        r.setRemark(remark);
        r.setCreateBy(SecurityUtils.getCurrentUserId());
        putawayDao.insert(r);
        return r;
    }

    private Map<String, Object> result(InvPutaway record, List<Map<String, Object>> cands,
                                       boolean suspended, boolean alreadyConfirmed) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("record", toMap(record));
        out.put("candidates", cands);
        out.put("suspended", suspended);
        out.put("alreadyConfirmed", alreadyConfirmed);
        return out;
    }

    private Map<String, Object> toMap(InvPutaway r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("sourceType", r.getSourceType());
        m.put("sourceDocNo", r.getSourceDocNo());
        m.put("sourceLineNo", r.getSourceLineNo());
        m.put("warehouseCode", r.getWarehouseCode());
        m.put("itemCode", r.getItemCode());
        m.put("batchNo", r.getBatchNo());
        m.put("qty", r.getQty());
        m.put("binCode", r.getBinCode());
        m.put("status", r.getStatus());
        m.put("supersededBy", r.getSupersededBy());
        m.put("remark", r.getRemark());
        m.put("createBy", r.getCreateBy());
        m.put("createDate", r.getCreateDate());
        return m;
    }

    private MdmItem itemOf(String itemCode) {
        if (itemCode == null || itemCode.trim().isEmpty()) {
            return null;
        }
        return itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                .eq(MdmItem::getItemCode, itemCode));
    }

    private LocalDate expiryOf(String itemCode, String batch) {
        if (itemCode == null || batch == null || batch.isEmpty()) {
            return null;
        }
        InvBatch b = batchDao.selectOne(new LambdaQueryWrapper<InvBatch>()
                .eq(InvBatch::getItemCode, itemCode)
                .eq(InvBatch::getBatchNo, batch));
        return b == null ? null : b.getExpiryDate();
    }

    private void requireWrite(String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> userRoles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> userRoles.add(a.getAuthority()));
        if (userRoles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
            return;
        }
        for (String r : userRoles) {
            if ("ROLE_WAREHOUSE".equalsIgnoreCase(r)) {
                return;
            }
        }
        throw new ServiceException(403, "无权限" + action);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    private static String strip(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
