package com.erp.service.impl.eam;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.eam.EamCipCostDao;
import com.erp.dao.eam.EamCipProjectDao;
import com.erp.entity.eam.EamCipCost;
import com.erp.entity.eam.EamCipProject;
import com.erp.service.eam.EamCipService;
import com.erp.util.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CIP 台账实现（add-asset-capitalization，design D2）：
 * 归集类别限工程款/安装费/资本化利息（BR-4.13-08 口径）；项目状态机 OPEN→COMPLETED→CLOSED。
 */
@Service
public class EamCipServiceImpl implements EamCipService {

    /** 归集类别三类（BR-4.13-08） */
    private static final List<String> COST_TYPES = List.of("ENGINGEE", "INSTALL", "INTEREST");

    private final EamCipProjectDao projectDao;
    private final EamCipCostDao costDao;

    public EamCipServiceImpl(EamCipProjectDao projectDao, EamCipCostDao costDao) {
        this.projectDao = projectDao;
        this.costDao = costDao;
    }

    @Override
    public Page<EamCipProject> page(long current, long size, String keyword, String status) {
        LambdaQueryWrapper<EamCipProject> qw = new LambdaQueryWrapper<EamCipProject>()
                .eq(status != null && !status.isEmpty(), EamCipProject::getStatus, status)
                .and(keyword != null && !keyword.isEmpty(), w -> w
                        .like(EamCipProject::getProjectNo, keyword)
                        .or().like(EamCipProject::getName, keyword))
                .orderByDesc(EamCipProject::getCreateDate);
        return projectDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    @Transactional
    public EamCipProject createProject(EamCipProject project) {
        if (isBlank(project.getName())) {
            throw new ServiceException(422, "项目名称必填");
        }
        project.setId(null);
        project.setProjectNo(nextProjectNo());
        project.setStatus("OPEN");
        project.setSettledAmount(BigDecimal.ZERO);
        project.setCreateBy(SecurityUtils.getCurrentUserId());
        projectDao.insert(project);
        return project;
    }

    @Override
    @Transactional
    public EamCipProject updateProject(EamCipProject project) {
        EamCipProject old = require(project.getId());
        if (!"OPEN".equals(old.getStatus())) {
            throw new ServiceException(422, "仅在建中项目可修改（当前 " + old.getStatus() + "）");
        }
        EamCipProject upd = new EamCipProject();
        upd.setId(old.getId());
        upd.setVerNo(old.getVerNo());
        upd.setName(project.getName());
        upd.setBudgetAmount(project.getBudgetAmount());
        upd.setOwnerName(project.getOwnerName());
        upd.setRemark(project.getRemark());
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        if (projectDao.updateById(upd) == 0) {
            throw new ServiceException(409, "项目更新冲突，请刷新后重试");
        }
        return projectDao.selectById(old.getId());
    }

    @Override
    @Transactional
    public EamCipCost addCost(EamCipCost cost) {
        EamCipProject project = require(cost.getProjectId());
        if (!"OPEN".equals(project.getStatus())) {
            throw new ServiceException(422, "仅在建中项目可归集成本（当前 " + project.getStatus() + "）");
        }
        String type = cost.getCostType() == null ? "" : cost.getCostType().trim().toUpperCase();
        if (!COST_TYPES.contains(type)) {
            throw new ServiceException(422, "归集类别取值域：工程款 ENGINGEE / 安装费 INSTALL / 资本化利息 INTEREST");
        }
        if (cost.getAmount() == null || cost.getAmount().signum() <= 0) {
            throw new ServiceException(422, "归集金额必须大于 0");
        }
        cost.setId(null);
        cost.setCostType(type);
        if (cost.getCostDate() == null) {
            cost.setCostDate(LocalDate.now());
        }
        cost.setCreateBy(SecurityUtils.getCurrentUserId());
        costDao.insert(cost);
        return cost;
    }

    @Override
    public Map<String, Object> detail(String projectId) {
        EamCipProject project = require(projectId);
        List<EamCipCost> costs = costDao.selectList(new LambdaQueryWrapper<EamCipCost>()
                .eq(EamCipCost::getProjectId, projectId)
                .orderByDesc(EamCipCost::getCostDate));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", project);
        out.put("costs", costs);
        out.put("costSummary", costSummary(projectId));
        out.put("costTotal", costDao.sumTotal(projectId));
        return out;
    }

    @Override
    public List<Map<String, Object>> costSummary(String projectId) {
        List<Map<String, Object>> rows = costDao.sumByType(projectId);
        // 三类固定输出（无明细补零），供前端表格稳定渲染
        Map<String, BigDecimal> byType = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Object t = r.get("costType");
            Object a = r.get("amount");
            if (t != null && a instanceof BigDecimal bd) {
                byType.put(String.valueOf(t), bd);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String t : COST_TYPES) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("costType", t);
            row.put("amount", byType.getOrDefault(t, BigDecimal.ZERO));
            out.add(row);
        }
        return out;
    }

    @Override
    @Transactional
    public EamCipProject complete(String projectId, LocalDate date) {
        EamCipProject p = require(projectId);
        if ("COMPLETED".equals(p.getStatus())) {
            return p; // 幂等
        }
        if (!"OPEN".equals(p.getStatus())) {
            throw new ServiceException(422, "仅在建中项目可完工验收（当前 " + p.getStatus() + "）");
        }
        if (costDao.sumTotal(projectId).signum() <= 0) {
            throw new ServiceException(422, "无成本归集的项目不可完工验收（请先归集工程款/安装费/利息）");
        }
        EamCipProject upd = new EamCipProject();
        upd.setId(p.getId());
        upd.setVerNo(p.getVerNo());
        upd.setStatus("COMPLETED");
        upd.setCompletionDate(date == null ? LocalDate.now() : date);
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        if (projectDao.updateById(upd) == 0) {
            throw new ServiceException(409, "完工验收状态更新冲突，请刷新后重试");
        }
        return projectDao.selectById(p.getId());
    }

    private EamCipProject require(String id) {
        EamCipProject p = id == null ? null : projectDao.selectById(id);
        if (p == null) {
            throw new ServiceException(404, "在建工程项目不存在：" + id);
        }
        return p;
    }

    private String nextProjectNo() {
        String prefix = "CIP" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = projectDao.selectMaxSeq(prefix);
        return prefix + String.format("%04d", (max == null ? 0 : max) + 1);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
