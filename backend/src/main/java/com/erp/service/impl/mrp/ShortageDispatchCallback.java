package com.erp.service.impl.mrp;

import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.mrp.DispatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 缺料派工审批回调（change add-dispatch-management，spec dispatch-management
 * 「缺料派工 L2 审批」C-4.5-12；approval-workflow 同事务回调）。
 * bizId = moId#opSeq；通过 → 生成缺料先行派工单（跳 L1、打标、刷新预扣基数）+ 推送申请人；
 * 驳回 → 不生成，推送申请人。回调异常 → 审批与业务一并回滚。
 */
@Slf4j
@Component
public class ShortageDispatchCallback implements ApprovalCallback {

    private final DispatchService dispatchService;

    public ShortageDispatchCallback(DispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(DispatchServiceImpl.BIZ_SHORTAGE);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        String[] parts = splitBiz(instance.getBizId());
        dispatchService.approveShortage(parts[0], Integer.valueOf(parts[1]),
                instance.getApplyBy(), instance.getApprNo());
        log.info("ShortageDispatch {} approved: {}", instance.getApprNo(), instance.getBizId());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        String[] parts = splitBiz(instance.getBizId());
        dispatchService.rejectShortage(parts[0], Integer.valueOf(parts[1]),
                instance.getApplyBy(), instance.getApprNo());
        log.info("ShortageDispatch {} rejected: {}", instance.getApprNo(), instance.getBizId());
    }

    private String[] splitBiz(String bizId) {
        if (bizId == null || !bizId.contains("#")) {
            throw new IllegalStateException("ShortageDispatch bizId 格式非法（moId#opSeq）：" + bizId);
        }
        return bizId.split("#", 2);
    }
}
