package com.erp.service.impl.proc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.proc.FrameworkAgreementDao;
import com.erp.dao.proc.FrameworkAgreementLineDao;
import com.erp.dao.proc.PriceControlLogDao;
import com.erp.dao.proc.ProcBudgetDao;
import com.erp.dao.proc.PurchaseOrderLineDao;
import com.erp.entity.proc.FrameworkAgreement;
import com.erp.entity.proc.FrameworkAgreementLine;
import com.erp.entity.proc.PriceControlLog;
import com.erp.entity.proc.ProcBudget;
import com.erp.entity.proc.PurchaseOrder;
import com.erp.entity.proc.PurchaseOrderLine;
import com.erp.service.proc.PriceControlService;
import com.erp.service.proc.ProcBudgetService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 价控三重校验引擎（design D6，spec purchase-price-control）。
 * L1 合同价 := 框架协议价（偏差 D1，协议模块 11.11 未实现期间以协议承接）：
 *   有协议价 → 判 PASS/ESCALATE（>协议价×(1+PRICE_TOLERANCE)）并结束该行（BR-4.2-19 命中即停）；
 *   无协议价 → 降级 L2。
 * L2 历史价 := 近 3 次已下达 PO 均价：0 次 NO_HISTORY 放行（偏差 D2）；
 *   偏离 > TOLERANCE_DEFAULT(0.5%) → BLOCK（BR-4.2-16，可特批转升级）。
 * L3 预算 := 科目本年累计 + 本次 > 预算 × 110% → ESCALATE（BR-4.2-17 附明细）；未设科目 NO_BUDGET 放行。
 * 仅当存在 L1 未命中的行时才执行 L3（全部 L1 命中 → 结束校验，spec 场景「合同价命中即停」）。
 */
@Slf4j
@Service
public class PriceControlServiceImpl implements PriceControlService {

    @Value("${app.proc.price-tolerance:0.05}")
    private BigDecimal priceTolerance;

    @Value("${app.proc.history-price-tolerance:0.005}")
    private BigDecimal historyPriceTolerance;

    private final PriceControlLogDao logDao;
    private final PurchaseOrderLineDao lineDao;
    private final FrameworkAgreementDao agreementDao;
    private final FrameworkAgreementLineDao agreementLineDao;
    private final ProcBudgetService budgetService;
    private final com.erp.dao.mdm.MdmItemDao itemDao;

    public PriceControlServiceImpl(PriceControlLogDao logDao,
                                   PurchaseOrderLineDao lineDao,
                                   FrameworkAgreementDao agreementDao,
                                   FrameworkAgreementLineDao agreementLineDao,
                                   ProcBudgetService budgetService,
                                   com.erp.dao.mdm.MdmItemDao itemDao) {
        this.logDao = logDao;
        this.lineDao = lineDao;
        this.agreementDao = agreementDao;
        this.agreementLineDao = agreementLineDao;
        this.budgetService = budgetService;
        this.itemDao = itemDao;
    }

    @Override
    @Transactional
    public List<Map<String, Object>> execute(PurchaseOrder po, List<PurchaseOrderLine> lines,
                                             String specialReason) {
        boolean special = specialReason != null && !specialReason.trim().isEmpty();
        if (special && specialReason.trim().length() < 2) {
            throw new ServiceException(422, "特批原因须不少于 2 字");
        }
        // 重跑（驳回重提）：仅保留本次结果
        logDao.delete(new LambdaQueryWrapper<PriceControlLog>()
                .eq(PriceControlLog::getPoId, po.getId()));

        List<PriceControlLog> written = new ArrayList<>();
        boolean anyLevel2 = false;      // 存在 L1 未命中的行
        List<String> blocks = new ArrayList<>();

        // 寄售 PO（CONSIGN）价控与预算豁免（add-consignment-procurement design D13）：
        // 行价在创建时已按 VMI 协议价回填，C-4.2-09 于领用物权转移时点校验协议有效性；
        // 物权未转移不构成采购成本，L3 预算亦不在此占用。逐行留痕 PASS 放行。
        if ("CONSIGN".equals(po.getPoType())) {
            for (PurchaseOrderLine l : lines) {
                written.add(writeLog(po, l, "CONTRACT", l.getUnitPrice(), l.getUnitPrice(),
                        "PASS", "寄售 PO 价控豁免（价格由 VMI 协议管控，design D13）"));
                l.setPriceCtrlResult("PASS");
                lineDao.updateById(l);
            }
            log.info("PO {} consignment PO, price control/budget skipped ({} lines PASS)",
                    po.getPoNo(), lines.size());
            return toRows(written);
        }

        // ---- L1/L2 逐行 ----
        for (PurchaseOrderLine l : lines) {
            BigDecimal agreementPrice = activeAgreementPrice(l, po.getSupplierId());
            if (agreementPrice != null) {
                // L1 命中（含协议来源带出行与同物料其他生效协议）
                boolean over = l.getUnitPrice() != null
                        && l.getUnitPrice().compareTo(agreementPrice
                        .multiply(BigDecimal.ONE.add(priceTolerance)).setScale(4, RoundingMode.HALF_UP)) > 0;
                String result = over ? "ESCALATE" : "PASS";
                String reason = over
                        ? "行 " + l.getLineNo() + " 单价 " + l.getUnitPrice() + " 超协议价 "
                        + agreementPrice + " ×(1+" + priceTolerance + ")（C-4.2-06 / BR-4.2-15）"
                        : null;
                written.add(writeLog(po, l, "CONTRACT", agreementPrice, l.getUnitPrice(),
                        result, reason));
                l.setPriceCtrlResult(result);
                lineDao.updateById(l);
                continue;
            }
            // L2 历史价
            anyLevel2 = true;
            List<BigDecimal> history = lineDao.historyPrices(l.getItemCode(), po.getId());
            if (history == null || history.isEmpty()) {
                written.add(writeLog(po, l, "HISTORY", null, l.getUnitPrice(),
                        "NO_HISTORY", "无历史采购记录，冷启动放行（偏差 D2）"));
                l.setPriceCtrlResult("NO_HISTORY");
                lineDao.updateById(l);
                continue;
            }
            BigDecimal avg = history.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(history.size()), 4, RoundingMode.HALF_UP);
            BigDecimal dev = l.getUnitPrice().subtract(avg).abs()
                    .divide(avg, 6, RoundingMode.HALF_UP);
            if (dev.compareTo(historyPriceTolerance) > 0) {
                String result = special ? "ESCALATE" : "BLOCK";
                String reason = (special ? "特批：" + specialReason.trim() + "；" : "")
                        + "行 " + l.getLineNo() + " 单价 " + l.getUnitPrice()
                        + " 偏离历史均价 " + avg + " 达 " + dev.multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP) + "%（> "
                        + historyPriceTolerance.multiply(BigDecimal.valueOf(100)) + "%，BR-4.2-16）";
                written.add(writeLog(po, l, "HISTORY", avg, l.getUnitPrice(), result, reason));
                if (!special) {
                    blocks.add("行 " + l.getLineNo() + " " + l.getItemCode()
                            + " 偏离历史均价 " + dev.multiply(BigDecimal.valueOf(100))
                            .setScale(2, RoundingMode.HALF_UP) + "%");
                }
                l.setPriceCtrlResult(result);
                lineDao.updateById(l);
            } else {
                written.add(writeLog(po, l, "HISTORY", avg, l.getUnitPrice(), "PASS", null));
                l.setPriceCtrlResult("PASS");
                lineDao.updateById(l);
            }
        }

        // ---- L1 全命中 → 结束（不再查历史与预算）----
        if (!anyLevel2) {
            log.info("PO {} price control: all lines resolved at CONTRACT, skip HISTORY/BUDGET",
                    po.getPoNo());
            return toRows(written);
        }

        // ---- 存在 BLOCK 且未特批 → 阻断提交 ----
        if (!blocks.isEmpty()) {
            throw new ServiceException(422, "价控校验未通过（BR-4.2-16 历史价偏离硬阻断）："
                    + String.join("；", blocks)
                    + "。可填写原因后「申请特批」转入升级审批链（FR-4.2-3-1）");
        }

        // ---- L3 预算 ----
        BudgetCheck bc = budgetCheck(po);
        if (bc.notConfigured) {
            written.add(writeLog(po, null, "BUDGET", null, po.getTotalAmt(),
                    "NO_BUDGET", "科目未设年度预算，放行（spec 场景「未设预算放行」）"));
        } else if (bc.over) {
            written.add(writeLog(po, null, "BUDGET", bc.trigger, bc.actual,
                    "ESCALATE", "本年累计 " + bc.used + " + 本次 " + po.getTotalAmt()
                    + " > 预算 " + bc.budget + " × " + bc.triggerPct + "%（BR-4.2-17 / C-4.2-03），"
                    + "升级采购总监并附预算使用明细"));
        } else {
            written.add(writeLog(po, null, "BUDGET", bc.trigger, bc.actual, "PASS", null));
        }
        log.info("PO {} price control done: logs={} budget={}", po.getPoNo(), written.size(),
                bc.notConfigured ? "NO_BUDGET" : (bc.over ? "ESCALATE" : "PASS"));
        return toRows(written);
    }

    @Override
    public List<Map<String, Object>> logs(String poId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PriceControlLog l : logDao.selectList(new LambdaQueryWrapper<PriceControlLog>()
                .eq(PriceControlLog::getPoId, poId)
                .orderByAsc(PriceControlLog::getCheckLevel)
                .orderByAsc(PriceControlLog::getLineNo))) {
            out.add(toRow(l));
        }
        return out;
    }

    // ---------- 私有 ----------

    /**
     * L1 基准（偏差 D1）：优先该行自身协议行（协议下单）；否则按协议创建时间倒序遍历
     * 生效中/临期协议，取该 PO 供应商名下该物料的最新协议价；无同供应商协议时回退最新任意协议价。
     */
    private BigDecimal activeAgreementPrice(PurchaseOrderLine l, String supplierId) {
        if (l.getAgreementLineId() != null) {
            FrameworkAgreementLine al = agreementLineDao.selectById(l.getAgreementLineId());
            if (al != null && agreementActive(al.getAgreementId())) {
                return al.getUnitPrice();
            }
        }
        // 先取生效协议（按创建倒序 → 最新协议价优先），再在协议内找该物料行
        List<FrameworkAgreement> actives = agreementDao.selectList(
                new LambdaQueryWrapper<FrameworkAgreement>()
                        .in(FrameworkAgreement::getStatus, "1", "2")
                        .orderByDesc(FrameworkAgreement::getCreateDate)
                        .last("LIMIT 100"));
        BigDecimal fallback = null;
        for (FrameworkAgreement fa : actives) {
            FrameworkAgreementLine hit = agreementLineDao.selectOne(
                    new LambdaQueryWrapper<FrameworkAgreementLine>()
                            .eq(FrameworkAgreementLine::getAgreementId, fa.getId())
                            .eq(FrameworkAgreementLine::getItemCode, l.getItemCode())
                            .last("LIMIT 1"));
            if (hit == null) {
                continue;
            }
            if (supplierId != null && supplierId.equals(hit.getAwardSupplierId())) {
                return hit.getUnitPrice();   // 该供应商的最新协议价
            }
            if (fallback == null) {
                fallback = hit.getUnitPrice();
            }
        }
        return fallback;
    }

    private boolean agreementActive(String agreementId) {
        if (agreementId == null) {
            return false;
        }
        FrameworkAgreement fa = agreementDao.selectById(agreementId);
        return fa != null && ("1".equals(fa.getStatus()) || "2".equals(fa.getStatus()));
    }

    private static class BudgetCheck {
        boolean notConfigured;
        boolean over;
        BigDecimal budget;
        BigDecimal used;
        BigDecimal trigger;
        BigDecimal actual;
        String triggerPct;
    }

    /** 科目任一触发即超（与 PoApprovalServiceImpl 判级同口径，BR-4.2-17） */
    private BudgetCheck budgetCheck(PurchaseOrder po) {
        BudgetCheck r = new BudgetCheck();
        int year = LocalDate.now().getYear();
        boolean anyConfigured = false;
        BudgetCheck best = null;
        for (String cat : categoriesOf(po.getId())) {
            Map<String, Object> full = budgetService.budgetOf(cat, year);
            if (full == null) {
                continue;
            }
            anyConfigured = true;
            BudgetCheck c = new BudgetCheck();
            c.budget = (BigDecimal) full.get("budgetAmt");
            c.used = (BigDecimal) full.get("usedAmt");
            c.trigger = (BigDecimal) full.get("triggerAmt");
            c.actual = c.used.add(po.getTotalAmt() == null ? BigDecimal.ZERO : po.getTotalAmt());
            Object tp = full.get("trigger");
            c.triggerPct = tp instanceof BigDecimal
                    ? ((BigDecimal) tp).multiply(BigDecimal.valueOf(100))
                    .stripTrailingZeros().toPlainString() : "110";
            c.over = c.actual.compareTo(c.trigger) > 0;
            if (c.over && best == null) {
                best = c;          // 优先记录触发升级的科目
            } else if (best == null) {
                best = c;          // 未触发时记录任一已配置科目（PASS 日志用）
            }
        }
        r.notConfigured = !anyConfigured;
        if (best != null) {
            r.budget = best.budget;
            r.used = best.used;
            r.trigger = best.trigger;
            r.actual = best.actual;
            r.triggerPct = best.triggerPct;
            r.over = best.over;
        }
        return r;
    }

    /** PO 行涉及的物料品类（去重） */
    private List<String> categoriesOf(String poId) {
        List<String> out = new ArrayList<>();
        for (PurchaseOrderLine l : lineDao.selectList(new LambdaQueryWrapper<PurchaseOrderLine>()
                .eq(PurchaseOrderLine::getPoId, poId))) {
            String cat = itemCategory(l.getItemCode());
            if (cat != null && !out.contains(cat)) {
                out.add(cat);
            }
        }
        return out;
    }

    private String itemCategory(String itemCode) {
        if (itemCode == null) {
            return null;
        }
        com.erp.entity.mdm.MdmItem item = itemDao.selectOne(
                new LambdaQueryWrapper<com.erp.entity.mdm.MdmItem>()
                        .eq(com.erp.entity.mdm.MdmItem::getItemCode, itemCode)
                        .last("LIMIT 1"));
        return item == null ? null : item.getCategoryCode();
    }

    private PriceControlLog writeLog(PurchaseOrder po, PurchaseOrderLine line, String level,
                                     BigDecimal base, BigDecimal actual,
                                     String result, String reason) {
        PriceControlLog l = new PriceControlLog();
        l.setPoId(po.getId());
        l.setPoNo(po.getPoNo());
        l.setLineNo(line == null ? null : line.getLineNo());
        l.setItemCode(line == null ? null : line.getItemCode());
        l.setCheckLevel(level);
        l.setBaseValue(base);
        l.setActualValue(actual);
        l.setResult(result);
        l.setReason(reason);
        l.setChkBy(SecurityUtils.getCurrentUserId());
        logDao.insert(l);
        return l;
    }

    private List<Map<String, Object>> toRows(List<PriceControlLog> logs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PriceControlLog l : logs) {
            out.add(toRow(l));
        }
        return out;
    }

    private Map<String, Object> toRow(PriceControlLog l) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", l.getId());
        row.put("poNo", l.getPoNo());
        row.put("lineNo", l.getLineNo());
        row.put("itemCode", l.getItemCode());
        row.put("checkLevel", l.getCheckLevel());
        row.put("baseValue", l.getBaseValue());
        row.put("actualValue", l.getActualValue());
        row.put("result", l.getResult());
        row.put("reason", l.getReason());
        row.put("chkDate", l.getChkDate());
        return row;
    }
}
