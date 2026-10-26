package com.erp.service.mrp;

import com.erp.entity.mrp.MrpMo;

import java.util.List;
import java.util.Map;

/**
 * 试产工单（change add-trial-production-management，spec trial-order）。
 * 申请单 APPROVED 后 1:1 转开 TP- 工单（FR-4.5-8-3），建单口收口 C-4.5-14 卡控
 * （BOM/路线 TRIAL 态放行 / 正式已发布确认 / 其余阻断）与 PVT 一致性 diff 审批（BR-4.5-35）。
 */
public interface TrialOrderService {

    /**
     * 1:1 转开试产工单。
     * 校验链（顺序执行、任一失败零工单零快照）：①申请单 APPROVED 且未转开；②唯一在途
     * （TRIAL 池按阶段细分）；③BOM 状态卡控（C-4.5-14）；④路线状态卡控；⑤PVT diff（BR-4.5-35）。
     *
     * @param trialNo      申请单号
     * @param bomId        指定 BOM 版本（空 = 自动优选 TRIAL 态，次选已发布需确认）
     * @param routingId    指定路线版本（空 = 同上）
     * @param ackFormalBom 正式已发布 BOM/路线确认标记（C-4.5-07 同类提示，确认后放行留痕）
     * @return {"mo": MrpMo, "warnings": [...]} 或 PVT 差异待审批时 {"pendingDiff": true, ...}
     */
    Map<String, Object> createFromApply(String trialNo, String bomId, String routingId,
                                        boolean ackFormalBom);

    /** 试产工单列表（TRIAL 分池；阶段/状态过滤） */
    List<MrpMo> list(String trialStage, String status);

    /**
     * 生命周期阶段推导（spec trial-order「生命周期与阶段推导」，零状态机改动）：
     * EXECUTING 未完工 / PENDING_INSP 完工未出检验结论 / PENDING_STOCK_IN 检验放行未入库过账 /
     * PENDING_REVIEW 入库完成无总结评审 / PENDING_CLOSE 评审 APPROVED 未关单 / CLOSED 关单终态。
     */
    String phaseOf(String moId);

    /**
     * 完工 TRIAL 分支（Reporting completeMo 同事务调用，spec trial-execution
     * 「产出入样品仓与放行前置」FR-4.5-8-5 / BR-4.5-34）：
     * ①合格产出经引擎 WIP_IN 入样品仓 QC_QTY（不入 AVAILABLE——不产生可销售库存）；
     * ②建 OQC 检验批 refId=<moId>#TRIAL；③料工费归集入试产成本表（TRIAL: 重建式）。
     * 三步各自幂等（冲销重入安全）。返回归集与入库摘要。
     */
    Map<String, Object> onCompleted(MrpMo mo, java.math.BigDecimal qualified);

    /**
     * 试产成本明细与汇总（spec trial-execution「财务确认后计入汇总」）：
     * 明细全量返回；汇总 = 归集行合计 − 已确认（CONFIRMED）冲回额——
     * PENDING_FINANCE 冲回 MUST NOT 计入（design 风险「确认前剔除 PENDING 行」）。
     */
    Map<String, Object> costOf(String moId);

    /**
     * 转量产复制（FR-4.5-8-7 / design D11）：①校验总结评审 APPROVED（否则 422）；
     * ②克隆试产 BOM 与路线为量产 DRAFT（TRIAL_STAGE=NULL、SRC_TRIAL_NO=TP 单号留痕，
     * 版本号自动分配），原试产版零改动；③返回两版本号供前端跳转既有发布页
     * （发布提交与签署两处再校验来源评审，防评审状态回退）。
     */
    Map<String, Object> promote(String moId);

    /**
     * 试产剩余物料明细（L2 提示源，关单预检⑤ / FR-4.5-8-8）：
     * 本工单 POSTED 领料行 × TRIAL_FLAG=1 批次 × 当前可用余量 > 0。
     */
    List<Map<String, Object>> remainderOf(String moId);

    /**
     * 剩余物料确认留痕（关单 L2 确认闸）：存在余量时写 notice 台账
     * （BIZ_TYPE=TRIAL_REMAINDER_ACK），TrialCloseChecks 据此放行关闭；
     * 无余量亦可调用（幂等空操作留痕）。
     */
    Map<String, Object> ackRemainder(String moId, String note);
}
