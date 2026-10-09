package com.erp.service.crm;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.crm.Contract;

import java.util.List;
import java.util.Map;

/**
 * 销售合同（tasks 14.2~14.6，spec sales-contract，D10/D11）。
 *
 * 口径：
 *  - 合同编号系统生成且创建后不可改；商机带出名称/客户/预期金额（FR-4.8-1-7）；
 *  - 金额与商机预期差异 > 10% → L2 审批（销售经理→销售总监），≤10% 直接进法务审核；
 *  - 法务审核占位：审核人与意见必填，驳回退回修改并注明原因；
 *  - 合同 → SO → 应收链路（D10）：SO 关联来源合同并回写 SO_COUNT，应收经 SO 可回溯；
 *  - 收款计划挂合同、不参与记账；达成对比复用 ArService.planProgress（实时口径）；
 *  - 变更生成新版本历史只读、销售经理审批（金额差异扩大重跑 >10% 校验）、
 *    变更后提示收款计划待调整（14.6）。
 */
public interface ContractService {

    Page<Contract> page(long current, long size, String keyword, String status,
                        String customerId);

    /** 详情：头 + 名下 SO + 计划达成 + 应收汇总 + 版本历史 + 审批日志 + 调整提示 */
    Map<String, Object> detail(String id);

    /** 14.2 生成草稿：商机带出（oppId 可空=手工），CT_NO 生成锁定，差异率计算 */
    Contract createDraft(Map<String, Object> req);

    /** 草稿/被驳回可改（编号锁定、商机金额基准不可改） */
    Contract update(String id, Map<String, Object> req);

    /** 14.2 提交：差异 >10% → L2（销售经理→销售总监）；否则直接进法务审核 */
    Contract submit(String id);

    /**
     * 14.2 法务审核占位：审核人与意见必填；pass → 签订（SIGNED，记录签订人/时间）；
     * 驳回 → 退回 DRAFT 并记录原因（LEGAL_REVIEW_OPINION 留痕）。
     */
    Contract legalReview(String id, boolean pass, String reviewer, String opinion);

    /** 14.4 收款计划全量保存（期数唯一/金额>0/到期日必填），返回合计与合同额的调整提示 */
    Map<String, Object> savePlans(String contractId, List<Map<String, Object>> plans);

    /** 删除期次（不参与记账，随时可删） */
    void removePlan(String planId);

    /** 14.5 计划达成：逐期计划/实际应收/核销/达成率/超期 + 合同应收汇总 + 名下 SO */
    Map<String, Object> progress(String contractId);

    /**
     * 14.6 变更（金额/交期/标题）：生成新版本快照并提交审批（销售经理；
     * 金额差异扩大 >10% → L2 加销售总监），通过后生效、驳回可重新发起。
     */
    Contract change(String id, Map<String, Object> payload);

    /** 版本历史（只读，含快照与变更说明） */
    List<Map<String, Object>> versions(String id);
}
