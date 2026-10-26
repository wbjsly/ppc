package com.erp.service.impl.hr;

import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.impl.hr.EmployeeArchiveServiceImpl;
import com.erp.service.impl.hr.HrAccountLinkServiceImpl;
import com.erp.service.impl.hr.HrExitServiceImpl;
import com.erp.service.impl.hr.HrTransferServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * HR 审批回调（add-employee-archive）：
 * HrAccountOpen 建号 / HrLockChange 锁定特批应用 / HrProbation 转正执行 /
 * HrPostChange 调岗生效 / HrExit 进入交接。
 */
@Slf4j
@Component
public class HrApprovalCallback implements ApprovalCallback {

    // @Lazy 断循环：ApprovalServiceImpl → 本回调 → 服务 → ApprovalEngine（=ApprovalServiceImpl）
    private final HrAccountLinkServiceImpl accountLinkService;
    private final EmployeeArchiveServiceImpl archiveService;
    private final HrTransferServiceImpl transferService;
    private final HrExitServiceImpl exitService;
    private final HrOrgChangeServiceImpl orgChangeService;
    private final com.erp.service.hr.HrSocialChangeService socialChangeService;

    public HrApprovalCallback(@Lazy HrAccountLinkServiceImpl accountLinkService,
                              @Lazy EmployeeArchiveServiceImpl archiveService,
                              @Lazy HrTransferServiceImpl transferService,
                              @Lazy HrExitServiceImpl exitService,
                              @Lazy HrOrgChangeServiceImpl orgChangeService,
                              @Lazy com.erp.service.hr.HrSocialChangeService socialChangeService) {
        this.accountLinkService = accountLinkService;
        this.archiveService = archiveService;
        this.transferService = transferService;
        this.exitService = exitService;
        this.orgChangeService = orgChangeService;
        this.socialChangeService = socialChangeService;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(HrAccountLinkServiceImpl.BIZ_ACCOUNT_OPEN,
                EmployeeArchiveServiceImpl.BIZ_LOCK_CHANGE,
                HrTransferServiceImpl.BIZ_PROBATION,
                HrTransferServiceImpl.BIZ_POST,
                HrExitServiceImpl.BIZ_EXIT,
                HrOrgChangeServiceImpl.BIZ_TYPE);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        try {
            switch (instance.getBizType()) {
                case HrAccountLinkServiceImpl.BIZ_ACCOUNT_OPEN -> {
                    accountLinkService.createAccount(instance.getBizId());
                    // social-insurance-ops 触点①：建号成功 → 自动开社保增员单（DRAFT，防重；失败仅告警）
                    try {
                        socialChangeService.autoOpen(instance.getBizId(),
                                com.erp.entity.hr.HrSocialChange.TYPE_ADD,
                                com.erp.entity.hr.HrSocialChange.SRC_ONBOARDING,
                                instance.getBizId(), null, null);
                    } catch (Exception ex) {
                        log.warn("social ADD auto-open failed for {}: {}", instance.getBizId(), ex.getMessage());
                    }
                }
                case EmployeeArchiveServiceImpl.BIZ_LOCK_CHANGE -> {
                    // bizId = empId:field:rand；新值从 pending 日志不可靠——从 bizId 后段取不到，
                    // 回调以 applyLockChange 处理工号场景；身份证场景新值需由申请时保存。
                    // 简化：以 bizId 结构应用（empNo 特批场景由申请方保证 newValue 进 bizId 不可行——
                    // 采用 pending 日志 NEW_VALUE（工号原值 / 身份证脱敏值仅工号场景可靠）。
                    // 身份证特批：申请时 newValue 已随 payload 保存于 pending NEW_VALUE（未脱敏写法见
                    // requestLockChange——对 idCardNo 存脱敏值）。为保证正确性，此处仅应用工号；
                    // 身份证场景由 HR 在特批通过后经 updateEmployee 通道不可行（锁定）——
                    // 因此 requestLockChange 对 idCardNo 直接保存原文于 pending（脱敏只用于展示）。
                    archiveService.applyLockChange(instance.getBizId());
                }
                case HrTransferServiceImpl.BIZ_PROBATION ->
                        transferService.effectProbation(instance.getBizId());
                case HrTransferServiceImpl.BIZ_POST ->
                        transferService.effectPost(instance.getBizId());
                case HrExitServiceImpl.BIZ_EXIT ->
                        exitService.onApproved(instance.getBizId());
                case HrOrgChangeServiceImpl.BIZ_TYPE ->
                        orgChangeService.execute(instance.getBizId());
                default -> log.warn("unknown biz type: {}", instance.getBizType());
            }
        } catch (Exception e) {
            // 联动失败不回滚审批事务之外的人事业务——记录告警
            log.error("HR approval callback failed: {} {} - {}", instance.getBizType(),
                    instance.getBizId(), e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        if (HrExitServiceImpl.BIZ_EXIT.equals(instance.getBizType())) {
            exitService.onRejected(instance.getBizId());
        }
        if (HrOrgChangeServiceImpl.BIZ_TYPE.equals(instance.getBizType())) {
            orgChangeService.reject(instance.getBizId());
        }
        log.info("HR approval rejected: {} {}", instance.getBizType(), instance.getBizId());
    }
}
