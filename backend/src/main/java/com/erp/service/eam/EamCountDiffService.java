package com.erp.service.eam;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.eam.EamCountDiff;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 盘点差异处理（add-asset-count，spec asset-count，design D2/D5/D6）。
 * 自动比对四类差异 → 调查留痕 → 逐单分级审批 → 盘盈自动带价建卡（BR-27）/
 * 盘亏挂账→责任认定→核销（BR-28）→ 标签异常冻结调拨处置（BR-30）。
 */
public interface EamCountDiffService {

    Page<EamCountDiff> page(long current, long size, String planId, String diffType, String status);

    Map<String, Object> detail(String id);

    /**
     * 自动比对（FR-4.13-5-4）：实盘 vs 快照逐项 →
     * OVER（SURPLUS 实物有账无）/ SHORT（MISSING 账有实物无）/ STATUS_CONFLICT /
     * PENDING_CONFIRM（UNKNOWN 待确认）生成差异单；已生成的跳过（幂等）。
     * 盘盈自动带出建议价（同分类 12 月转资原值均价，BR-27）。
     */
    Map<String, Object> autoCompare(String planId);

    /** 差异原因调查留痕（查无原因升级财务经理 notice） */
    Map<String, Object> investigate(String id, String reason, String evidence);

    /**
     * 逐单提交分级审批（D2）：DIFF_AMOUNT vs ASSET_COUNT_DIFF_THRESHOLD
     * 不足阈值 → 部门主管 → 财务资产会计；达到阈值 → 追加财务经理 → 总经理。
     */
    Map<String, Object> submitApproval(String id);

    /** 审批通过回调入口：OVER→READY_TO_POST / SHORT→LIABILITY_PENDING / 冻结类→CLOSED+冻结 */
    Map<String, Object> onApprovalPassed(String id);

    /** 审批驳回回调入口：回调查态留因 */
    Map<String, Object> onApprovalRejected(String id, String reason);

    /**
     * 盘亏责任认定（BR-28）：NONE/COMPENSATE/INSURANCE 三结论留痕；
     * COMPENSATE 须赔偿金额；认定完成置 READY_TO_POST。
     */
    Map<String, Object> liability(String id, String liabilityType, String liabilityPerson,
                                  BigDecimal compensateAmount, String basis);

    /**
     * 账务执行（D5）：盘盈 → 建卡 + 盘盈凭证（借固定资产/贷 6301）；
     * 盘亏 → 核销凭证（借 6711 净损失 + 借 1122 赔偿 / 贷 1901）+ 卡片 DISPOSED → POSTED→CLOSED。
     */
    Map<String, Object> postAdjust(String id);

    /** 盘亏挂账（审批通过即执行：NBV 挂 1901，认定前不得核销 BR-28） */
    Map<String, Object> suspendShortage(String id);

    /** 标签异常冻结/解除（BR-30）：置/清卡片 COUNT_FREEZE_FLAG */
    Map<String, Object> freezeTag(String cardId, String diffId, boolean freeze);

    /** 冻结卡片列表（7.5.2 冻结管理） */
    java.util.List<Map<String, Object>> frozenCards();

    /** 解冻并关闭标签异常差异单（补打/更换标签后） */
    Map<String, Object> closeTagIssue(String diffId);
}
