package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.mrp.MrpMoDao;
import com.erp.dao.mrp.TrialCostDao;
import com.erp.entity.inv.InvStock;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.TrialCost;
import com.erp.entity.qms.InspectionLot;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.qms.LotReleaseListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 试产放行回调（change add-trial-production-management，spec trial-execution
 * 「放行转可用与计价冲回」BR-4.5-34 / design D8）。
 * confirmRelease 同事务，refId 后缀 #TRIAL 分派：
 * ①样品仓 QC_QTY −= n / AVAILABLE_QTY += n 平移（QTY 守恒，行锁 + 非负护栏）；
 * ②REVERSAL 冲回分录（金额 = n × STANDARD_COST，借存货/贷研发费用，PENDING_FINANCE）；
 * ③自动送 TrialCostReverse 财务确认审批（单节点 ROLE_FINANCE_MGR）。
 * 幂等锚 = 该检验批 REVERSAL 分录已存在（二次回调零平移零重复入账）。
 */
@Slf4j
@Component
public class TrialStockListener implements LotReleaseListener {

    /** 冲回财务确认审批 BIZ_TYPE（bizId = 试产成本行 ID） */
    public static final String BIZ_COST_REVERSE = "TrialCostReverse";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final InvStockDao stockDao;
    private final TrialCostDao trialCostDao;
    private final MdmItemDao mdmItemDao;
    private final MrpMoDao moDao;
    private final com.erp.service.SysParamService sysParam;
    private final ApprovalEngine approvalEngine;

    public TrialStockListener(InvStockDao stockDao, TrialCostDao trialCostDao,
                              MdmItemDao mdmItemDao, MrpMoDao moDao,
                              com.erp.service.SysParamService sysParam,
                              @org.springframework.context.annotation.Lazy ApprovalEngine approvalEngine) {
        this.stockDao = stockDao;
        this.trialCostDao = trialCostDao;
        this.mdmItemDao = mdmItemDao;
        this.moDao = moDao;
        this.sysParam = sysParam;
        this.approvalEngine = approvalEngine;
    }

    @Override
    @Transactional
    public void onReleased(InspectionLot lot) {
        // 仅试产 OQC 批（refId=<moId>#TRIAL 且合格放行）触发；其他批忽略
        if (lot == null || !"WORKORDER".equals(lot.getRefType())
                || lot.getRefId() == null || !lot.getRefId().endsWith("#TRIAL")) {
            return;
        }
        if (!"PASS".equals(lot.getResult()) && !"RELEASED".equals(lot.getStatus())) {
            return;
        }
        String moId = lot.getRefId().substring(0, lot.getRefId().length() - "#TRIAL".length());
        MrpMo mo = moDao.selectById(moId);
        if (mo == null || !MrpMo.MO_TYPE_TRIAL.equals(mo.getMoType())) {
            log.warn("试产放行回调跳过（工单缺失或非试产）{}", moId);
            return;
        }
        BigDecimal qty = lot.getLotQty() == null ? BigDecimal.ZERO : lot.getLotQty();
        if (qty.signum() <= 0) {
            log.warn("试产放行回调跳过（放行数量非正）{} qty={}", lot.getLotNo(), qty);
            return;
        }
        // 幂等锚：该检验批 REVERSAL 分录已存在 → 二次回调零平移零重复入账
        Long prior = trialCostDao.selectCount(new LambdaQueryWrapper<TrialCost>()
                .eq(TrialCost::getLotId, lot.getId())
                .eq(TrialCost::getCostType, TrialCost.T_REVERSAL));
        if (prior != null && prior > 0) {
            log.info("试产放行幂等跳过（冲回分录已存在）{} 批 {}", lot.getLotNo(), lot.getBatchNo());
            return;
        }

        String wh = sysParam.getValue("TRIAL_SAMPLE_WH", "WH-SAMPLE");
        String batchNo = lot.getBatchNo() == null ? "" : lot.getBatchNo();
        // 行锁样品仓批次行 → 平移（QTY 守恒，非负护栏）
        InvStock row = stockDao.selectOne(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getWarehouseCode, wh)
                .eq(InvStock::getItemCode, lot.getItemCode())
                .eq(InvStock::getBatchNo, batchNo)
                .last("FOR UPDATE"));
        if (row == null) {
            throw new ServiceException(422, "试产放行失败：样品仓不存在该批次库存行（"
                    + wh + "/" + lot.getItemCode() + "/" + batchNo + "）");
        }
        BigDecimal qc = row.getQcQty() == null ? BigDecimal.ZERO : row.getQcQty();
        if (qc.compareTo(qty) < 0) {
            throw new ServiceException(422, "试产放行失败：样品仓质检量不足（需 " + qty
                    + "，当前 " + qc + "）——状态异常请核对入库流水");
        }
        stockDao.update(null, new LambdaUpdateWrapper<InvStock>()
                .eq(InvStock::getId, row.getId())
                .setSql("QC_QTY = QC_QTY - " + qty.toPlainString())
                .setSql("AVAILABLE_QTY = AVAILABLE_QTY + " + qty.toPlainString()));
        // QTY 不做任何变更（池间平移守恒）

        // REVERSAL 冲回分录：金额 = n × STANDARD_COST（TRIAL_RELEASE_COST_BASIS=STANDARD 档，D2）
        BigDecimal stdCost = standardCost(lot.getItemCode());
        BigDecimal amount = qty.multiply(stdCost);
        TrialCost rev = new TrialCost();
        rev.setEntryNo(nextEntryNo());
        rev.setMoId(mo.getId());
        rev.setMoNo(mo.getMoNo());
        rev.setCostType(TrialCost.T_REVERSAL);
        rev.setAmount(amount);
        rev.setExpenseType("RD");
        rev.setDrCr("DR存货/CR研发费用");
        rev.setBatchNo(batchNo);
        rev.setQty(qty);
        rev.setLotId(lot.getId());
        rev.setFinStatus(TrialCost.FIN_PENDING);
        rev.setRemark("试产放行转库存冲回：" + lot.getLotNo() + " × " + qty
                + " × 标准成本 " + stdCost.toPlainString());
        insertResolvingConflict(rev);

        // 财务确认送签（单节点 ROLE_FINANCE_MGR，ADMIN 可签——design D8）
        com.erp.entity.system.ApprovalInstance inst = approvalEngine.submit(
                BIZ_COST_REVERSE, rev.getId(),
                "试产转库存计价冲回确认：" + mo.getMoNo() + " 金额 " + amount.toPlainString(),
                null, List.of(List.of(ApprovalNodeSpec.sign("ROLE_FINANCE_MGR", "财务主管"))));
        trialCostDao.update(null, new LambdaUpdateWrapper<TrialCost>()
                .eq(TrialCost::getId, rev.getId())
                .set(TrialCost::getApprovalId, inst.getId()));
        log.info("试产放行 {}：QC→AVAILABLE {}，冲回分录 {}（{}）已送财务确认",
                mo.getMoNo(), qty, rev.getEntryNo(), amount);
    }

    // ================= 私有助手 =================

    /** 计价基准（TRIAL_RELEASE_COST_BASIS=STANDARD → 物料标准成本列；NRV 档预留，D2 偏差） */
    private BigDecimal standardCost(String itemCode) {
        MdmItem item = mdmItemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                .eq(MdmItem::getItemCode, itemCode).last("LIMIT 1"));
        return item == null || item.getStandardCost() == null
                ? BigDecimal.ZERO : item.getStandardCost();
    }

    /** TC+yyyyMMdd+6 位流水（冲突递增，与归集行同号池） */
    private String nextEntryNo() {
        String prefix = "TC-" + LocalDate.now().format(DAY) + "-";
        long cnt = trialCostDao.selectCount(new LambdaQueryWrapper<TrialCost>()
                .likeRight(TrialCost::getEntryNo, prefix));
        return prefix + String.format("%06d", cnt + 1);
    }

    private void insertResolvingConflict(TrialCost row) {
        for (int attempt = 0; attempt < 99; attempt++) {
            try {
                trialCostDao.insert(row);
                return;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                row.setEntryNo(bumpEntryNo(row.getEntryNo()));
            }
        }
        throw new ServiceException(422, "试产冲回分录号分配冲突超限，请稍后重试");
    }

    private String bumpEntryNo(String entryNo) {
        int dash = entryNo.lastIndexOf('-');
        long n = Long.parseLong(entryNo.substring(dash + 1)) + 1;
        return entryNo.substring(0, dash + 1) + String.format("%06d", n);
    }
}
