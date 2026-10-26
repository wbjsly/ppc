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
import com.erp.service.pm.PmBudgetChangeService;
import com.erp.service.pm.PmRateService;
import com.erp.util.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 预算变更双签（PMO → FINANCE，project-initiation BR-4.8-12）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmBudgetChangeServiceImpl implements PmBudgetChangeService {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final PmBudgetChangeDao changeDao;
    private final PmBudgetLineDao lineDao;
    private final PmProjectDao projectDao;
    private final PmWbsDao wbsDao;
    private final PmRateService rateService;
    private final ApprovalEngine approvalEngine;
    private final PmChangeInterceptor changeInterceptor;

    @Override
    public List<Map<String, Object>> list(String projectId) {
        LambdaQueryWrapper<PmBudgetChange> qw = new LambdaQueryWrapper<PmBudgetChange>()
                .eq(PmBudgetChange::getDelFlag, "0")
                .orderByDesc(PmBudgetChange::getCreateDate);
        if (projectId != null && !projectId.isBlank()) {
            qw.eq(PmBudgetChange::getProjectId, projectId);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PmBudgetChange c : changeDao.selectList(qw)) {
            out.add(toRow(c));
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> submit(String projectId, Map<String, Object> payload) {
        PmProject p = requireProject(projectId);
        if (!"1".equals(p.getBaselineLocked())) {
            throw new ServiceException(422, "仅基线锁定后的项目可提交预算变更申请");
        }
        // 变更拦截（C-4.8-09，add-project-change）：项目存在在途变更时不可修改基线
        changeInterceptor.assertNoPendingProjectChange(projectId);
        Long pending = changeDao.selectCount(new LambdaQueryWrapper<PmBudgetChange>()
                .eq(PmBudgetChange::getProjectId, projectId)
                .eq(PmBudgetChange::getStatus, PmBudgetChange.ST_PENDING)
                .eq(PmBudgetChange::getDelFlag, "0"));
        if (pending != null && pending > 0) {
            throw new ServiceException(422, "已有待审批的变更申请");
        }
        String reason = payload.get("reason") == null ? null : String.valueOf(payload.get("reason")).trim();
        if (reason == null || reason.length() < 2) {
            throw new ServiceException(422, "变更理由必填（至少 2 字）");
        }
        Object rawLines = payload.get("lines");
        if (!(rawLines instanceof List<?> list) || list.isEmpty()) {
            throw new ServiceException(422, "变更后预算行不能为空");
        }
        // 构建快照：重算人工/间接，汇总 after 金额
        List<Map<String, Object>> snapshot = new ArrayList<>();
        BigDecimal after = BigDecimal.ZERO;
        Map<String, PmWbs> taskById = new LinkedHashMap<>();
        for (PmWbs w : wbsDao.selectList(new LambdaQueryWrapper<PmWbs>()
                .eq(PmWbs::getProjectId, projectId).eq(PmWbs::getDelFlag, "0"))) {
            taskById.put(w.getId(), w);
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> row)) {
                continue;
            }
            String wbsId = row.get("wbsId") == null ? null : String.valueOf(row.get("wbsId"));
            if (wbsId == null || wbsId.isBlank()) {
                continue;
            }
            PmWbs task = taskById.get(wbsId);
            BigDecimal hours = task != null && task.getEstHours() != null ? task.getEstHours() : BigDecimal.ZERO;
            BigDecimal rate = rateService.rateFor(task == null ? null : task.getOwnerGrade(), p.getRateOverride());
            BigDecimal labor = hours.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal material = dec(row.get("material"));
            BigDecimal subcontract = dec(row.get("subcontract"));
            BigDecimal travel = dec(row.get("travel"));
            BigDecimal other = dec(row.get("other"));
            BigDecimal five = labor.add(material).add(subcontract).add(travel).add(other);
            BigDecimal fenplan = p.getFenplanRate() != null ? p.getFenplanRate() : new BigDecimal("0.10");
            BigDecimal indirect = five.multiply(fenplan).setScale(2, RoundingMode.HALF_UP);
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("wbsId", wbsId);
            snap.put("labor", labor);
            snap.put("material", material);
            snap.put("subcontract", subcontract);
            snap.put("travel", travel);
            snap.put("other", other);
            snap.put("indirect", indirect);
            snapshot.add(snap);
            after = after.add(labor).add(material).add(subcontract).add(travel).add(other).add(indirect);
        }
        if (snapshot.isEmpty()) {
            throw new ServiceException(422, "变更后预算行不能为空");
        }
        PmBudgetChange c = new PmBudgetChange();
        c.setId(uuid());
        c.setChangeNo(nextChangeNo());
        c.setProjectId(projectId);
        c.setReason(reason);
        c.setBeforeAmount(p.getBaselineAmount());
        c.setAfterAmount(after);
        try {
            c.setSnapshotJson(mapper.writeValueAsString(snapshot));
        } catch (Exception e) {
            throw new ServiceException(422, "快照序列化失败");
        }
        c.setStatus(PmBudgetChange.ST_PENDING);
        c.setCreateBy(SecurityUtils.getCurrentUserId());
        c.setCreateDate(LocalDateTime.now());
        changeDao.insert(c);
        // 双节点串行：PMO → FINANCE
        var inst = approvalEngine.submit(
                PmApprovalCallback.BIZ_BUDGET_CHANGE,
                c.getId(),
                "预算变更：" + p.getName() + "（" + p.getProjectNo() + "）",
                null,
                List.of(
                        List.of(ApprovalNodeSpec.sign("ROLE_PMO", "PMO 审批")),
                        List.of(ApprovalNodeSpec.sign("ROLE_FINANCE", "财务审批"))));
        c.setApprId(inst.getId());
        changeDao.updateById(c);
        Map<String, Object> out = toRow(c);
        out.put("apprId", inst.getId());
        return out;
    }

    // ================================================================
    // helpers
    // ================================================================

    private String nextChangeNo() {
        String prefix = "PBC" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long max = changeDao.selectList(new LambdaQueryWrapper<PmBudgetChange>()
                        .likeRight(PmBudgetChange::getChangeNo, prefix)
                        .select(PmBudgetChange::getChangeNo)).stream()
                .map(PmBudgetChange::getChangeNo)
                .mapToInt(no -> {
                    try {
                        return Integer.parseInt(no.substring(no.lastIndexOf('-') + 1));
                    } catch (Exception e) {
                        return 0;
                    }
                }).max().orElse(0);
        return String.format("%s-%03d", prefix, max + 1);
    }

    private Map<String, Object> toRow(PmBudgetChange c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("changeNo", c.getChangeNo());
        m.put("projectId", c.getProjectId());
        m.put("reason", c.getReason());
        m.put("beforeAmount", c.getBeforeAmount());
        m.put("afterAmount", c.getAfterAmount());
        m.put("diffAmount", c.getAfterAmount().subtract(c.getBeforeAmount()));
        m.put("status", c.getStatus());
        m.put("pmOpinion", c.getPmOpinion());
        m.put("finOpinion", c.getFinOpinion());
        m.put("createBy", c.getCreateBy());
        m.put("createDate", c.getCreateDate());
        return m;
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

    private static BigDecimal dec(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
