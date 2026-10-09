package com.erp.service.impl.qms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.qms.InspectionStandardDao;
import com.erp.dao.qms.StandardVersionDao;
import com.erp.entity.qms.InspectionStandard;
import com.erp.entity.qms.StandardVersion;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 检验标准发布审批回调（BIZ_TYPE = StandardPublish，spec inspection-standard）。
 * 通过 → 版本置 RELEASED、旧版本生效止截为新版本生效前一日、current_version 推进（BR-4.12-57）；
 * 驳回 → 版本回 DRAFT 可修改重提。整个回调与审批在同事务内（ApprovalEngine 回调契约）。
 */
@Slf4j
@Component
public class StandardPublishCallback implements ApprovalCallback {

    private final StandardVersionDao versionDao;
    private final InspectionStandardDao standardDao;

    public StandardPublishCallback(StandardVersionDao versionDao, InspectionStandardDao standardDao) {
        this.versionDao = versionDao;
        this.standardDao = standardDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("StandardPublish");
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        StandardVersion v = versionDao.selectById(instance.getBizId());
        if (v == null) {
            log.warn("StandardPublish approved but version missing: {}", instance.getBizId());
            return;
        }
        if ("RELEASED".equals(v.getStatus())) {
            return; // 幂等
        }
        if (!"PENDING_APPROVE".equals(v.getStatus())) {
            throw new ServiceException(422, "版本状态不可发布：" + v.getStatus());
        }
        InspectionStandard s = standardDao.selectById(v.getStandardId());
        if (s == null) {
            throw new ServiceException(422, "标准不存在，无法发布");
        }

        // 旧版本生效止截为新版本生效前一日（区间不重叠）
        if (v.getEffectiveFrom() != null) {
            List<StandardVersion> released = versionDao.selectList(new LambdaQueryWrapper<StandardVersion>()
                    .eq(StandardVersion::getStandardId, v.getStandardId())
                    .eq(StandardVersion::getStatus, "RELEASED")
                    .ne(StandardVersion::getId, v.getId()));
            for (StandardVersion old : released) {
                if (old.getEffectiveTo() == null || !old.getEffectiveTo().isBefore(v.getEffectiveFrom())) {
                    old.setEffectiveTo(v.getEffectiveFrom().minusDays(1));
                    versionDao.updateById(old);
                }
            }
        }

        v.setStatus("RELEASED");
        v.setReleasedBy(instance.getApplyBy());
        v.setReleasedDate(java.time.LocalDateTime.now());
        v.setApprovalId(instance.getId());
        if (versionDao.updateById(v) == 0) {
            throw new ServiceException(422, "发布状态更新冲突");
        }

        s.setStatus("ACTIVE");
        s.setCurrentVersion(v.getVersionNo());
        if (standardDao.updateById(s) == 0) {
            throw new ServiceException(422, "标准状态更新冲突");
        }
        log.info("inspection standard published: {} V{}", s.getStandardCode(), v.getVersionNo());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        StandardVersion v = versionDao.selectById(instance.getBizId());
        if (v == null || "RELEASED".equals(v.getStatus())) {
            return;
        }
        v.setStatus("DRAFT");
        v.setApprovalId(instance.getId());
        versionDao.updateById(v);
        log.info("inspection standard publish rejected: versionId={}", v.getId());
    }
}
