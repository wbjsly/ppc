package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.pm.PmChangeWbsDao;
import com.erp.dao.pm.PmProjectChangeDao;
import com.erp.entity.pm.PmChangeWbs;
import com.erp.entity.pm.PmProjectChange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 倒签与先干后批拦截（add-project-change，C-4.8-09）。
 * 变更审批通过前，针对该 WBS 集的支出/基线操作按 WBS 精准拦截，不误伤项目其他正常支出。
 * 沿 add-cost-collection-freeze 的 422 口径（规格 L1 在 API 层体现为 422 业务码）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PmChangeInterceptor {

    private final PmChangeWbsDao changeWbsDao;
    private final PmProjectChangeDao changeDao;

    /**
     * 校验该 WBS 无未通过的项目变更（DRAFT/APPROVING 在途即拦）。
     * @param wbsId 目标 WBS 任务；null/blank 跳过（不拦）
     * @throws ServiceException 422 存在在途变更时
     */
    public void assertNoPendingChange(String projectId, String wbsId) {
        if (wbsId == null || wbsId.isBlank()) {
            return;
        }
        // 查该 WBS 关联的在途（DRAFT/APPROVING）变更单
        for (PmChangeWbs cw : changeWbsDao.selectList(new LambdaQueryWrapper<PmChangeWbs>()
                .eq(PmChangeWbs::getWbsId, wbsId)
                .eq(PmChangeWbs::getDelFlag, "0"))) {
            PmProjectChange c = changeDao.selectById(cw.getChangeId());
            if (c == null || "1".equals(c.getDelFlag())) {
                continue;
            }
            boolean pending = PmProjectChange.ST_DRAFT.equals(c.getStatus())
                    || PmProjectChange.ST_APPROVING.equals(c.getStatus());
            if (pending && (projectId == null || projectId.isBlank()
                    || projectId.equals(c.getProjectId()))) {
                throw new ServiceException(422,
                        "WBS 存在未通过的项目变更（单号 " + c.getChangeNo()
                                + "，状态 " + c.getStatus() + "），不可执行支出/基线操作（C-4.8-09 先审批后执行）");
            }
        }
    }

    /**
     * 校验项目无在途（DRAFT/APPROVING）变更（基线修改拦截，design D4 第 4 挂点）。
     * 项目级：任一在途变更即拦基线修改。
     */
    public void assertNoPendingProjectChange(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        Long pending = changeDao.selectCount(new LambdaQueryWrapper<PmProjectChange>()
                .eq(PmProjectChange::getProjectId, projectId)
                .in(PmProjectChange::getStatus,
                        PmProjectChange.ST_DRAFT, PmProjectChange.ST_APPROVING)
                .eq(PmProjectChange::getDelFlag, "0"));
        if (pending != null && pending > 0) {
            throw new ServiceException(422,
                    "项目存在未通过的项目变更（" + pending + " 单在途），不可修改基线（C-4.8-09 先审批后执行）");
        }
    }
}
