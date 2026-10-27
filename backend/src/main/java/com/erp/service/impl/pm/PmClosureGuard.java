package com.erp.service.impl.pm;

import com.erp.common.ServiceException;
import com.erp.entity.pm.PmProject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 归档只读守卫（add-project-closure，S-4.8-07）。
 * CLOSED 态五挂点 422「项目已归档」：工时/成本归集/变更发起/领料外协归集/基线提交；
 * CLOSING 态仅拦变更发起（提示撤回结项），工时/成本放行补录。
 * 五挂点统一走本类，避免各处重复 SQL（design D4）。
 */
@Component
public class PmClosureGuard {

    private final JdbcTemplate jdbcTemplate;

    public PmClosureGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 写入口校验。
     *
     * @param projectId 项目 ID（空则放行——未挂项目的单据）
     * @param strict    true=归档拦截点（工时/成本/领料/基线，CLOSED 拦）；
     *                  false=变更发起（CLOSING+CLOSED 均拦）
     */
    public void assertWritable(String projectId, boolean strict) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        String status = jdbcTemplate.queryForObject(
                "SELECT STATUS FROM erp_pm_project WHERE ID=?", String.class, projectId);
        if (status == null) {
            return;
        }
        if (PmProject.ST_CLOSED.equals(status)) {
            throw new ServiceException(422, "项目已归档，不可新增业务单据");
        }
        if (!strict && PmProject.ST_CLOSING.equals(status)) {
            throw new ServiceException(422, "项目已发起结项，请先撤回结项申请");
        }
    }
}
