package com.erp.service.impl.pm;

import com.erp.dao.pm.PmTravelClaimDao;
import com.erp.entity.pm.PmTravelClaim;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.pm.PmCostCollectService;
import com.erp.service.system.NoticeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * 差旅超标审批回调（add-cost-collection-freeze，FR-4.8-5-3 L2）。
 * BIZ_TYPE=PmTravelOver：通过 → 归集差旅成本 + 凭证（DEPT_ONLY 跳过）；驳回 → REJECTED。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PmTravelClaimApprovalCallback implements ApprovalCallback {

    public static final String BIZ_TRAVEL_OVER = PmTravelClaimServiceImpl.BIZ_TRAVEL_OVER;

    private final PmTravelClaimDao claimDao;
    private final PmCostCollectService costCollectService;
    private final NoticeService noticeService;

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(BIZ_TRAVEL_OVER);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        PmTravelClaim c = claimDao.selectById(instance.getBizId());
        if (c == null) {
            log.warn("travel over approved but claim missing: {}", instance.getBizId());
            return;
        }
        // 归集（DEPT_ONLY 内部跳过）
        if (!"1".equals(c.getDeptOnly())) {
            costCollectService.collect(PmCostCollectService.TYPE_TRAVEL,
                    PmCostCollectService.SRC_TRAVEL_CLAIM, c.getId(),
                    c.getProjectId(), c.getWbsId(), c.getAmount(), LocalDate.now());
        }
        c.setStatus(PmTravelClaim.ST_APPROVED);
        c.setReviewDate(LocalDateTime.now());
        c.setUpdateBy("system");
        c.setUpdateDate(LocalDateTime.now());
        claimDao.updateById(c);
        noticeService.push(null, c.getEmpId(),
                "差旅超标报销审批通过",
                "你 " + c.getStartDate() + " 至 " + c.getEndDate() + " 的超标差旅报销（"
                        + c.getAmount() + " 元）已通过财务审批并完成归集。",
                BIZ_TRAVEL_OVER, c.getId());
        log.info("travel over approved -> APPROVED: {} {}", c.getId(), c.getAmount());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        PmTravelClaim c = claimDao.selectById(instance.getBizId());
        if (c == null) {
            log.warn("travel over rejected but claim missing: {}", instance.getBizId());
            return;
        }
        c.setStatus(PmTravelClaim.ST_REJECTED);
        c.setRejectReason(lastRejectOpinion(instance.getId()));
        c.setUpdateBy("system");
        c.setUpdateDate(LocalDateTime.now());
        claimDao.updateById(c);
        noticeService.push(null, c.getEmpId(),
                "差旅超标报销审批驳回",
                "你 " + c.getStartDate() + " 的超标差旅报销被驳回：" + c.getRejectReason(),
                BIZ_TRAVEL_OVER, c.getId());
        log.info("travel over rejected -> REJECTED: {}", c.getId());
    }

    private String lastRejectOpinion(String apprId) {
        var tasks = taskDao.selectList(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<com.erp.entity.system.ApprovalTask>()
                .eq(com.erp.entity.system.ApprovalTask::getApprId, apprId)
                .eq(com.erp.entity.system.ApprovalTask::getStatus, "REJECTED"));
        if (tasks.isEmpty()) {
            return null;
        }
        return tasks.get(tasks.size() - 1).getOpinion();
    }

    private final com.erp.dao.system.ApprovalTaskDao taskDao;
}
