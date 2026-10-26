package com.erp.service.impl.mrp;

import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.system.NoticeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 二次返工审批回调（change add-rework-management，spec rework-execution
 * 「返工次数与二次返工审批」FR-4.5-7-6 / BR-4.5-02）。
 * bizId = 判定单号（srcNo）；通过 → 该判定单可创建第二张返工工单（create 口门禁校验 APPROVED 实例）；
 * 驳回 → 维持阻断，由质量侧重新评审。审批结果通知在回调内自推（5.6 D6 silent 纪律）。
 */
@Slf4j
@Component
public class ReworkSecondCallback implements ApprovalCallback {

    private final NoticeService noticeService;

    public ReworkSecondCallback(NoticeService noticeService) {
        this.noticeService = noticeService;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(ReworkOrderServiceImpl.BIZ_REWORK_SECOND);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        noticeService.push(null, instance.getApplyBy(),
                "二次返工审批通过", "判定单 " + instance.getBizId() + " 的二次返工审批 "
                        + instance.getApprNo() + " 已通过，可创建第二张返工工单",
                "REWORK_SECOND_RESULT", instance.getBizId());
        log.info("ReworkSecond {} approved: {}", instance.getApprNo(), instance.getBizId());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        noticeService.push(null, instance.getApplyBy(),
                "二次返工审批驳回", "判定单 " + instance.getBizId() + " 的二次返工审批 "
                        + instance.getApprNo() + " 被驳回（" + nvl(instance.getRemark())
                        + "），不可创建第二张返工工单",
                "REWORK_SECOND_RESULT", instance.getBizId());
        log.info("ReworkSecond {} rejected: {}", instance.getApprNo(), instance.getBizId());
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
