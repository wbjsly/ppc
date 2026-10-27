package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.pm.ClosureSettlementDao;
import com.erp.dao.pm.PmProjectDao;
import com.erp.entity.pm.ClosureSettlement;
import com.erp.entity.pm.PmProject;
import com.erp.service.pm.PmClosureSettlementService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 结算实现（design D5）：确认 = 解项目级成本冻结（FREEZE_FLAG=0）+ INSERT 不可变快照。
 * 快照无 UPDATE 业务路径（BR-4.8-29 十年归档锚点）；已存在 409。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmClosureSettlementServiceImpl implements PmClosureSettlementService {

    private final ClosureSettlementDao settlementDao;
    private final PmProjectDao projectDao;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public Map<String, Object> summary(String projectId) {
        requireRole("查看成本结算", "ROLE_PM", "ROLE_PMO", "ROLE_FINANCE", "ROLE_FIN_MGR");
        PmProject p = requireProject(projectId);
        BigDecimal collect = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(AMOUNT),0) FROM erp_pm_cost_collect "
                        + "WHERE PROJECT_ID=? AND DEL_FLAG='0'", BigDecimal.class, projectId);
        BigDecimal baseline = p.getBaselineAmount() == null ? BigDecimal.ZERO : p.getBaselineAmount();
        BigDecimal deviation;
        if (baseline.signum() == 0) {
            deviation = collect != null && collect.signum() > 0 ? BigDecimal.ONE : BigDecimal.ZERO;
        } else {
            deviation = collect.subtract(baseline).divide(baseline, 4, RoundingMode.HALF_UP);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId);
        out.put("projectNo", p.getProjectNo());
        out.put("collectTotal", collect == null ? BigDecimal.ZERO : collect);
        out.put("baselineAmt", baseline);
        out.put("deviationRate", deviation);
        out.put("freezeFlag", p.getFreezeFlag());
        out.put("freezeReason", p.getFreezeReason());
        out.put("settled", settlementOf(projectId));
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> confirm(String projectId) {
        requireRole("确认成本结算", "ROLE_FINANCE", "ROLE_FIN_MGR");
        PmProject p = requireProject(projectId);
        if (settlementDao.selectCount(new LambdaQueryWrapper<ClosureSettlement>()
                .eq(ClosureSettlement::getProjectId, projectId)
                .eq(ClosureSettlement::getDelFlag, "0")) > 0) {
            throw new ServiceException(409, "该项目已结算确认（快照不可变）");
        }
        BigDecimal collect = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(AMOUNT),0) FROM erp_pm_cost_collect "
                        + "WHERE PROJECT_ID=? AND DEL_FLAG='0'", BigDecimal.class, projectId);
        BigDecimal baseline = p.getBaselineAmount() == null ? BigDecimal.ZERO : p.getBaselineAmount();
        BigDecimal deviation;
        if (baseline.signum() == 0) {
            deviation = collect != null && collect.signum() > 0 ? BigDecimal.ONE : BigDecimal.ZERO;
        } else {
            deviation = collect.subtract(baseline).divide(baseline, 4, RoundingMode.HALF_UP);
        }
        // 解项目级成本冻结（账面收尾）
        boolean wasFrozen = PmProject.FREEZE_YES.equals(p.getFreezeFlag());
        if (wasFrozen) {
            p.setFreezeFlag(PmProject.FREEZE_NO);
            p.setFreezeReason(null);
            p.setFrozenAt(null);
            p.setUpdateBy(SecurityUtils.getCurrentUserId());
            p.setUpdateDate(LocalDateTime.now());
            projectDao.updateById(p);
        }
        ClosureSettlement s = new ClosureSettlement();
        s.setId(uuid());
        s.setProjectId(projectId);
        s.setProjectNo(p.getProjectNo());
        s.setCollectTotal(collect == null ? BigDecimal.ZERO : collect);
        s.setBaselineAmt(baseline);
        s.setDeviationRate(deviation);
        s.setUnfrozenFlag(wasFrozen ? "1" : "0");
        s.setConfirmBy(SecurityUtils.getCurrentUserId());
        s.setConfirmDate(LocalDateTime.now());
        s.setCreateBy(SecurityUtils.getCurrentUserId());
        s.setCreateDate(LocalDateTime.now());
        settlementDao.insert(s);
        log.info("closure settlement confirmed: {} total={} baseline={} dev={}",
                projectId, s.getCollectTotal(), s.getBaselineAmt(), deviation);
        return summary(projectId);
    }

    private Map<String, Object> settlementOf(String projectId) {
        ClosureSettlement s = settlementDao.selectOne(new LambdaQueryWrapper<ClosureSettlement>()
                .eq(ClosureSettlement::getProjectId, projectId)
                .eq(ClosureSettlement::getDelFlag, "0")
                .last("LIMIT 1"));
        if (s == null) {
            return Map.of();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("collectTotal", s.getCollectTotal());
        m.put("baselineAmt", s.getBaselineAmt());
        m.put("deviationRate", s.getDeviationRate());
        m.put("unfrozenFlag", s.getUnfrozenFlag());
        m.put("confirmBy", s.getConfirmBy());
        m.put("confirmDate", s.getConfirmDate());
        return m;
    }

    private PmProject requireProject(String projectId) {
        PmProject p = projectDao.selectById(projectId);
        if (p == null) {
            throw new ServiceException(404, "项目不存在：" + projectId);
        }
        return p;
    }

    private void requireRole(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        java.util.List<String> userRoles = new java.util.ArrayList<>();
        auth.getAuthorities().forEach(a -> userRoles.add(a.getAuthority()));
        if (userRoles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
            return;
        }
        for (String role : allowed) {
            if (userRoles.stream().anyMatch(r -> role.equalsIgnoreCase(r))) {
                return;
            }
        }
        throw new ServiceException(403, "无权" + action);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
