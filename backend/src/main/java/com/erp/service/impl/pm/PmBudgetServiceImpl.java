package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.pm.PmBudgetChangeDao;
import com.erp.dao.pm.PmBudgetLineDao;
import com.erp.dao.pm.PmProjectDao;
import com.erp.dao.pm.PmWbsDao;
import com.erp.entity.pm.PmBudgetChange;
import com.erp.entity.pm.PmBudgetLine;
import com.erp.entity.pm.PmProject;
import com.erp.entity.pm.PmWbs;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.pm.PmBudgetService;
import com.erp.service.pm.PmRateService;
import com.erp.service.pm.PmWbsService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 任务级预算与基线提交（project-initiation，FR-4.8-2-3/2-4）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmBudgetServiceImpl implements PmBudgetService {

    public static final String BIZ_BASELINE = "PmBaseline";

    private final PmBudgetLineDao lineDao;
    private final PmProjectDao projectDao;
    private final PmWbsDao wbsDao;
    private final PmBudgetChangeDao changeDao;
    private final PmRateService rateService;
    private final PmWbsService wbsService;
    private final ApprovalEngine approvalEngine;
    private final com.erp.service.pm.PmIpAgreementService ipAgreementService;

    @Override
    public Map<String, Object> lines(String projectId) {
        PmProject p = requireProject(projectId);
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, PmWbs> taskById = new LinkedHashMap<>();
        for (PmWbs w : wbsDao.selectList(new LambdaQueryWrapper<PmWbs>()
                .eq(PmWbs::getProjectId, projectId).eq(PmWbs::getDelFlag, "0"))) {
            taskById.put(w.getId(), w);
        }
        for (PmBudgetLine l : lineDao.selectList(new LambdaQueryWrapper<PmBudgetLine>()
                .eq(PmBudgetLine::getProjectId, projectId)
                .eq(PmBudgetLine::getDelFlag, "0"))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("wbsId", l.getWbsId());
            PmWbs task = taskById.get(l.getWbsId());
            m.put("wbsName", task == null ? null : task.getName());
            m.put("wbsCode", task == null ? null : task.getWbsCode());
            m.put("estHours", task == null ? null : task.getEstHours());
            m.put("ownerGrade", task == null ? null : task.getOwnerGrade());
            m.put("labor", l.getLabor());
            m.put("material", l.getMaterial());
            m.put("subcontract", l.getSubcontract());
            m.put("travel", l.getTravel());
            m.put("other", l.getOther());
            m.put("indirect", l.getIndirect());
            m.put("total", lineTotal(l));
            out.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", projectId);
        result.put("baselineLocked", p.getBaselineLocked());
        result.put("fenplanRate", p.getFenplanRate());
        result.put("lines", out);
        result.put("summary", sumLines(out));
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> saveLines(String projectId, Map<String, Object> payload) {
        PmProject p = requireProject(projectId);
        if ("1".equals(p.getBaselineLocked())) {
            throw new ServiceException(422, "基线已锁定，预算不可直接修改（请提交预算变更申请）");
        }
        if (!PmProject.ST_DRAFT.equals(p.getStatus())) {
            throw new ServiceException(422, "仅草稿态可编辑预算");
        }
        Object rawLines = payload.get("lines");
        if (!(rawLines instanceof List<?> list) || list.isEmpty()) {
            throw new ServiceException(422, "预算行不能为空");
        }
        int updated = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> row)) {
                continue;
            }
            String wbsId = row.get("wbsId") == null ? null : String.valueOf(row.get("wbsId"));
            if (wbsId == null || wbsId.isBlank()) {
                continue;
            }
            PmBudgetLine line = lineDao.selectOne(new LambdaQueryWrapper<PmBudgetLine>()
                    .eq(PmBudgetLine::getWbsId, wbsId)
                    .eq(PmBudgetLine::getDelFlag, "0")
                    .last("LIMIT 1"));
            if (line == null) {
                // 兜底建行（模板生成路径已建，这里容错）
                line = new PmBudgetLine();
                line.setId(java.util.UUID.randomUUID().toString().replace("-", ""));
                line.setProjectId(projectId);
                line.setWbsId(wbsId);
                line.setLabor(BigDecimal.ZERO);
                line.setMaterial(BigDecimal.ZERO);
                line.setSubcontract(BigDecimal.ZERO);
                line.setTravel(BigDecimal.ZERO);
                line.setOther(BigDecimal.ZERO);
                line.setIndirect(BigDecimal.ZERO);
                line.setCreateBy(SecurityUtils.getCurrentUserId());
                line.setCreateDate(LocalDateTime.now());
                lineDao.insert(line);
            }
            line.setMaterial(decimal(row.get("material")));
            line.setSubcontract(decimal(row.get("subcontract")));
            line.setTravel(decimal(row.get("travel")));
            line.setOther(decimal(row.get("other")));
            recalc(line, p);
            line.setUpdateBy(SecurityUtils.getCurrentUserId());
            line.setUpdateDate(LocalDateTime.now());
            lineDao.updateById(line);
            updated++;
        }
        // 重算项目基线金额（审批前的草稿基线合计）
        BigDecimal total = recomputeProjectBaseline(projectId, p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", updated);
        out.put("baselineAmountDraft", total);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> submitBaseline(String projectId) {
        PmProject p = requireProject(projectId);
        if (PmProject.ST_APPROVING.equals(p.getStatus())) {
            throw new ServiceException(422, "审批中不可重复提交");
        }
        if (!PmProject.ST_DRAFT.equals(p.getStatus())) {
            throw new ServiceException(422, "仅草稿态可提交基线审批");
        }
        // 立项校验（BR-4.8-10）
        List<String> wbsErrors = wbsService.validateForBaseline(projectId);
        if (!wbsErrors.isEmpty()) {
            throw new ServiceException(422, String.join("；", wbsErrors));
        }
        // IP 归属协议卡控（BR-4.8-24 / C-4.8-08，add-ip-management）：无协议项目不可启动
        if (ipAgreementService.effectiveOf(projectId) == null) {
            throw new ServiceException(422, "IP 归属协议缺失，请法务补充协议后再立项（BR-4.8-24）");
        }
        // 重算全部预算行 + 合计校验（BR-4.8-11）
        BigDecimal total = recomputeAllAndSum(projectId, p);
        if (total.signum() <= 0) {
            throw new ServiceException(422, "预算合计为 0，不可立项（FR-4.8-2-3）");
        }
        // 提交审批：单节点 PMO
        var inst = approvalEngine.submit(
                BIZ_BASELINE,
                projectId,
                "项目基线审批：" + p.getName(),
                null,
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_PMO", "PMO 基线审批"))));
        p.setStatus(PmProject.ST_APPROVING);
        p.setApprId(inst.getId());
        p.setBaselineAmount(total);
        p.setUpdateBy(SecurityUtils.getCurrentUserId());
        p.setUpdateDate(LocalDateTime.now());
        projectDao.updateById(p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId);
        out.put("apprId", inst.getId());
        out.put("baselineAmount", total);
        return out;
    }

    @Override
    public Map<String, Object> summary(String projectId) {
        requireProject(projectId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PmBudgetLine l : lineDao.selectList(new LambdaQueryWrapper<PmBudgetLine>()
                .eq(PmBudgetLine::getProjectId, projectId)
                .eq(PmBudgetLine::getDelFlag, "0"))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("labor", l.getLabor());
            m.put("material", l.getMaterial());
            m.put("subcontract", l.getSubcontract());
            m.put("travel", l.getTravel());
            m.put("other", l.getOther());
            m.put("indirect", l.getIndirect());
            m.put("total", lineTotal(l));
            rows.add(m);
        }
        return sumLines(rows);
    }

    // ================================================================
    // helpers
    // ================================================================

    /** 单行重算：人工 = 工时×费率；间接 = 前五项×分摊率 */
    private void recalc(PmBudgetLine line, PmProject project) {
        PmWbs task = wbsDao.selectById(line.getWbsId());
        BigDecimal hours = task != null && task.getEstHours() != null ? task.getEstHours() : BigDecimal.ZERO;
        BigDecimal rate = rateService.rateFor(task == null ? null : task.getOwnerGrade(), project.getRateOverride());
        line.setLabor(hours.multiply(rate).setScale(2, RoundingMode.HALF_UP));
        BigDecimal five = line.getLabor()
                .add(nz(line.getMaterial()))
                .add(nz(line.getSubcontract()))
                .add(nz(line.getTravel()))
                .add(nz(line.getOther()));
        BigDecimal fenplan = project.getFenplanRate() != null ? project.getFenplanRate() : new BigDecimal("0.10");
        line.setIndirect(five.multiply(fenplan).setScale(2, RoundingMode.HALF_UP));
    }

    /** 重算全部行并返回合计（提交审批用） */
    private BigDecimal recomputeAllAndSum(String projectId, PmProject project) {
        BigDecimal total = BigDecimal.ZERO;
        for (PmBudgetLine line : lineDao.selectList(new LambdaQueryWrapper<PmBudgetLine>()
                .eq(PmBudgetLine::getProjectId, projectId)
                .eq(PmBudgetLine::getDelFlag, "0"))) {
            recalc(line, project);
            line.setUpdateBy(SecurityUtils.getCurrentUserId());
            line.setUpdateDate(LocalDateTime.now());
            lineDao.updateById(line);
            total = total.add(lineTotal(line));
        }
        return total;
    }

    private BigDecimal recomputeProjectBaseline(String projectId, PmProject project) {
        BigDecimal total = BigDecimal.ZERO;
        for (PmBudgetLine line : lineDao.selectList(new LambdaQueryWrapper<PmBudgetLine>()
                .eq(PmBudgetLine::getProjectId, projectId)
                .eq(PmBudgetLine::getDelFlag, "0"))) {
            total = total.add(lineTotal(line));
        }
        project.setBaselineAmount(total);
        project.setUpdateBy(SecurityUtils.getCurrentUserId());
        project.setUpdateDate(LocalDateTime.now());
        projectDao.updateById(project);
        return total;
    }

    private static BigDecimal lineTotal(PmBudgetLine l) {
        return nz(l.getLabor()).add(nz(l.getMaterial())).add(nz(l.getSubcontract()))
                .add(nz(l.getTravel())).add(nz(l.getOther())).add(nz(l.getIndirect()));
    }

    private static Map<String, Object> sumLines(List<Map<String, Object>> rows) {
        Map<String, Object> s = new LinkedHashMap<>();
        String[] keys = {"labor", "material", "subcontract", "travel", "other", "indirect", "total"};
        for (String k : keys) {
            BigDecimal sum = BigDecimal.ZERO;
            for (Map<String, Object> r : rows) {
                Object v = r.get(k);
                if (v instanceof BigDecimal b) {
                    sum = sum.add(b);
                }
            }
            s.put(k, sum);
        }
        return s;
    }

    private PmProject requireProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new ServiceException(422, "项目 ID 必填");
        }
        PmProject p = projectDao.selectById(projectId);
        if (p == null || "1".equals(p.getDelFlag())) {
            throw new ServiceException(404, "项目不存在");
        }
        return p;
    }

    private static BigDecimal nz(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }

    private static BigDecimal decimal(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (Exception e) {
            throw new ServiceException(422, "金额格式非法：" + o);
        }
    }
}
