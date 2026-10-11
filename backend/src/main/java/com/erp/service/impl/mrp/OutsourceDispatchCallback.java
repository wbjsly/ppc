package com.erp.service.impl.mrp;

import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.mrp.DispatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 外协改派审批回调（change add-dispatch-management，spec outsourcing-dispatch
 * 「外协改派与 L2 审批」BR-4.5-21；approval-workflow 同事务回调）。
 * bizId = dispatchId#targetWcCode；通过 → 改派外协 WC + OUTSOURCE 标记 + 二次校验 + 推送申请人；
 * 驳回 → 派工保持原状，推送申请人。
 */
@Slf4j
@Component
public class OutsourceDispatchCallback implements ApprovalCallback {

    private final DispatchService dispatchService;

    public OutsourceDispatchCallback(DispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(DispatchServiceImpl.BIZ_OUTSOURCE);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        String[] parts = splitBiz(instance.getBizId());
        dispatchService.approveOutsource(parts[0], parts[1],
                instance.getApplyBy(), instance.getApprNo());
        log.info("OutsourceDispatch {} approved: {}", instance.getApprNo(), instance.getBizId());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        String[] parts = splitBiz(instance.getBizId());
        dispatchService.rejectOutsource(parts[0], instance.getApplyBy(), instance.getApprNo());
        log.info("OutsourceDispatch {} rejected: {}", instance.getApprNo(), instance.getBizId());
    }

    private String[] splitBiz(String bizId) {
        if (bizId == null || !bizId.contains("#")) {
            throw new IllegalStateException("OutsourceDispatch bizId 格式非法（dispatchId#wc）：" + bizId);
        }
        return bizId.split("#", 2);
    }
}
