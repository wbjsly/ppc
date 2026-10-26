package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvBatchDao;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.mrp.MrpMoOpDao;
import com.erp.dao.mrp.TrialCostDao;
import com.erp.dao.mrp.TrialDataDao;
import com.erp.dao.mrp.TrialSummaryDao;
import com.erp.dao.system.SysNoticeDao;
import com.erp.dao.vmi.MaterialIssueDao;
import com.erp.dao.vmi.MaterialIssueLineDao;
import com.erp.entity.inv.InvBatch;
import com.erp.entity.inv.InvStock;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoOp;
import com.erp.entity.mrp.TrialCost;
import com.erp.entity.mrp.TrialData;
import com.erp.entity.mrp.TrialSummary;
import com.erp.entity.system.SysNotice;
import com.erp.entity.vmi.MaterialIssue;
import com.erp.entity.vmi.MaterialIssueLine;
import com.erp.service.mrp.MoCloseCheck;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 试产工单关闭前置校验（change add-trial-production-management，spec trial-execution
 * 「试产成本归集与关单」关单预检 / MoCloseCheck 钩子，非 TRIAL 单短路空通过）：
 * ①数据采集完整（L1，FR-4.5-8-5）；②总结评审 APPROVED（L1，未经评审不得结案）；
 * ③成本归集已执行——MATERIAL 行存在（L1，FR-4.5-8-8）；
 * ④试产剩余物料（L2）——已领未耗的试产专用批次余量列出明细，
 *   须 {@code TrialOrderService.ackRemainder} 确认留痕（BIZ_TYPE=TRIAL_REMAINDER_ACK）
 *   后方可关闭（FR-4.5-8-8 异常口径「确认留痕后方可关闭」）。
 */
@Slf4j
@Component
public class TrialCloseChecks implements MoCloseCheck {

    /** 剩余料确认留痕 BIZ_TYPE（erp_sys_notice 台账，ack 与校验共用锚） */
    public static final String BIZ_REMAINDER_ACK = "TRIAL_REMAINDER_ACK";

    private final MrpMoOpDao moOpDao;
    private final TrialDataDao trialDataDao;
    private final TrialSummaryDao trialSummaryDao;
    private final TrialCostDao trialCostDao;
    private final MaterialIssueDao issueDao;
    private final MaterialIssueLineDao issueLineDao;
    private final InvBatchDao batchDao;
    private final InvStockDao stockDao;
    private final SysNoticeDao noticeDao;

    public TrialCloseChecks(MrpMoOpDao moOpDao, TrialDataDao trialDataDao,
                            TrialSummaryDao trialSummaryDao, TrialCostDao trialCostDao,
                            MaterialIssueDao issueDao, MaterialIssueLineDao issueLineDao,
                            InvBatchDao batchDao, InvStockDao stockDao, SysNoticeDao noticeDao) {
        this.moOpDao = moOpDao;
        this.trialDataDao = trialDataDao;
        this.trialSummaryDao = trialSummaryDao;
        this.trialCostDao = trialCostDao;
        this.issueDao = issueDao;
        this.issueLineDao = issueLineDao;
        this.batchDao = batchDao;
        this.stockDao = stockDao;
        this.noticeDao = noticeDao;
    }

    @Override
    public String name() {
        return "试产结案卡控（采集/评审/归集/剩余料确认）";
    }

    @Override
    public void validate(MrpMo mo) {
        if (mo == null || !"TRIAL".equals(mo.getMoType())) {
            return; // 非试产单短路（spec：本钩子仅 TRIAL 生效）
        }
        // ---- ① 数据完整性（L1）：DONE 工序缺采集行 → 阻断并列出清单（FR-4.5-8-5） ----
        List<MrpMoOp> doneOps = moOpDao.selectList(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, mo.getId())
                .eq(MrpMoOp::getOpStatus, "DONE"));
        if (!doneOps.isEmpty()) {
            List<TrialData> collected = trialDataDao.selectList(new LambdaQueryWrapper<TrialData>()
                    .eq(TrialData::getMoId, mo.getId()));
            Set<Integer> haveData = collected.stream()
                    .map(TrialData::getOpSeq).collect(Collectors.toSet());
            List<MrpMoOp> missing = doneOps.stream()
                    .filter(op -> !haveData.contains(op.getOpSeq()))
                    .toList();
            if (!missing.isEmpty()) {
                String detail = missing.stream()
                        .map(o -> "工序 " + o.getOpSeq() + " " + o.getOpName())
                        .collect(Collectors.joining("、"));
                throw new ServiceException(422,
                        "试产数据缺记录，不允许试产结案（FR-4.5-8-5），缺采集：" + detail);
            }
        }

        // ---- ② 总结评审 APPROVED（L1，FR-4.5-8-6/8：未经评审不得结案） ----
        TrialSummary summary = trialSummaryDao.selectOne(new LambdaQueryWrapper<TrialSummary>()
                .eq(TrialSummary::getMoId, mo.getId()).last("LIMIT 1"));
        if (summary == null || !TrialSummary.ST_APPROVED.equals(summary.getStatus())) {
            throw new ServiceException(422, "试产总结评审未通过，不允许结案（FR-4.5-8-6）："
                    + mo.getMoNo() + "（评审状态 "
                    + (summary == null ? "未建总结单" : summary.getStatus()) + "）");
        }

        // ---- ③ 成本归集已执行（L1，FR-4.5-8-8：材料行 = 归集执行锚） ----
        Long materialRows = trialCostDao.selectCount(new LambdaQueryWrapper<TrialCost>()
                .eq(TrialCost::getMoId, mo.getId())
                .eq(TrialCost::getCostType, TrialCost.T_MATERIAL));
        if (materialRows == null || materialRows == 0) {
            throw new ServiceException(422,
                    "试产成本归集未执行，不允许结案（FR-4.5-8-8）：" + mo.getMoNo());
        }

        // ---- ④ 剩余物料确认留痕（L2→确认闸：有余量且未 ack → 422 列明细） ----
        List<Map<String, Object>> remainder = remainderOf(mo);
        if (!remainder.isEmpty()) {
            boolean acked = noticeDao.selectCount(new LambdaQueryWrapper<SysNotice>()
                    .eq(SysNotice::getBizType, BIZ_REMAINDER_ACK)
                    .eq(SysNotice::getBizId, mo.getId())) > 0;
            if (!acked) {
                String detail = remainder.stream()
                        .map(r -> r.get("itemCode") + "/" + r.get("batchNo")
                                + " 余 " + r.get("availableQty"))
                        .collect(Collectors.joining("、"));
                throw new ServiceException(422, "试产剩余物料未处理/未确认，不允许结案"
                        + "（请退库或转样品库存后确认留痕，FR-4.5-8-8）：" + detail);
            }
        }
        log.debug("试产结案预检通过：{}（采集齐/评审 APPROVED/归集已执行/剩余料 {} 项已确认）",
                mo.getMoNo(), remainder.size());
    }

    /**
     * 试产剩余物料明细（L2 提示源，关单卡控与 precheck/ack 共用）：
     * 本工单 POSTED 领料行 × 所领批次 TRIAL_FLAG=1 × 当前可用余量 > 0。
     */
    public List<Map<String, Object>> remainderOf(MrpMo mo) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (mo == null) {
            return out;
        }
        List<String> issueIds = issueDao.selectList(new LambdaQueryWrapper<MaterialIssue>()
                        .eq(MaterialIssue::getWorkOrderNo, mo.getMoNo())
                        .eq(MaterialIssue::getStatus, MaterialIssue.ST_POSTED))
                .stream().map(MaterialIssue::getId).toList();
        if (issueIds.isEmpty()) {
            return out;
        }
        for (MaterialIssueLine line : issueLineDao.selectList(
                new LambdaQueryWrapper<MaterialIssueLine>()
                        .in(MaterialIssueLine::getIssueId, issueIds))) {
            if (line.getBatchNo() == null || line.getBatchNo().isBlank()) {
                continue;
            }
            InvBatch batch = batchDao.selectOne(new LambdaQueryWrapper<InvBatch>()
                    .eq(InvBatch::getItemCode, line.getItemCode())
                    .eq(InvBatch::getBatchNo, line.getBatchNo()).last("LIMIT 1"));
            if (batch == null || !"1".equals(batch.getTrialFlag())) {
                continue; // 仅试产专用批次（TRIAL_FLAG=1）计入剩余料
            }
            BigDecimal avail = stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                            .eq(InvStock::getItemCode, line.getItemCode())
                            .eq(InvStock::getBatchNo, line.getBatchNo()))
                    .stream().map(s -> s.getAvailableQty() == null ? BigDecimal.ZERO : s.getAvailableQty())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (avail.signum() > 0) {
                out.add(Map.of("itemCode", line.getItemCode(),
                        "itemName", line.getItemName() == null ? "" : line.getItemName(),
                        "batchNo", line.getBatchNo(), "availableQty", avail));
            }
        }
        return out;
    }
}
