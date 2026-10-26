package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.crm.ContractDao;
import com.erp.dao.pm.PmBudgetLineDao;
import com.erp.dao.pm.PmProjectDao;
import com.erp.dao.pm.PmWbsDao;
import com.erp.dao.system.SysUserDao;
import com.erp.entity.crm.Contract;
import com.erp.entity.pm.PmBudgetLine;
import com.erp.entity.pm.PmProject;
import com.erp.entity.pm.PmWbs;
import com.erp.entity.system.SysUser;
import com.erp.service.pm.PmProjectService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 项目立项（project-initiation，FR-4.8-2-1；11.3.1）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmProjectServiceImpl implements PmProjectService {

    private final PmProjectDao projectDao;
    private final PmWbsDao wbsDao;
    private final PmBudgetLineDao budgetLineDao;
    private final ContractDao contractDao;
    private final SysUserDao userDao;

    @Override
    @Transactional
    public PmProject create(Map<String, Object> payload) {
        PmProject p = new PmProject();
        p.setId(uuid());
        applyPayload(p, payload);
        p.setStatus(PmProject.ST_DRAFT);
        p.setBaselineLocked("0");
        p.setBaselineAmount(BigDecimal.ZERO);
        if (p.getFenplanRate() == null) {
            p.setFenplanRate(new BigDecimal("0.10"));
        }
        p.setStartNotified("0");
        p.setCreateBy(SecurityUtils.getCurrentUserId());
        p.setCreateDate(LocalDateTime.now());
        projectDao.insert(p);
        return p;
    }

    @Override
    @Transactional
    public PmProject update(String id, Map<String, Object> payload) {
        PmProject p = require(id);
        if (!PmProject.ST_DRAFT.equals(p.getStatus())) {
            throw new ServiceException(422, "仅草稿态项目可修改");
        }
        applyPayload(p, payload);
        p.setUpdateBy(SecurityUtils.getCurrentUserId());
        p.setUpdateDate(LocalDateTime.now());
        projectDao.updateById(p);
        return p;
    }

    @Override
    public List<Map<String, Object>> list(String status, String projType, String keyword) {
        LambdaQueryWrapper<PmProject> qw = new LambdaQueryWrapper<PmProject>()
                .eq(PmProject::getDelFlag, "0")
                .orderByDesc(PmProject::getCreateDate);
        if (hasText(status)) {
            qw.eq(PmProject::getStatus, status);
        }
        if (hasText(projType)) {
            qw.eq(PmProject::getProjType, projType);
        }
        if (hasText(keyword)) {
            qw.like(PmProject::getName, keyword);
        }
        // PM 数据范围裁剪：仅 PM 且非 PMO/ADMIN 时只见自己的项目
        if (!hasAnyRole("PMO", "ADMIN")) {
            qw.eq(PmProject::getPmUserId, SecurityUtils.getCurrentUserId());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PmProject p : projectDao.selectList(qw)) {
            out.add(toRow(p));
        }
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        PmProject p = require(id);
        Map<String, Object> out = toRow(p);
        // WBS 概览统计
        List<PmWbs> nodes = wbsDao.selectList(new LambdaQueryWrapper<PmWbs>()
                .eq(PmWbs::getProjectId, id)
                .eq(PmWbs::getDelFlag, "0"));
        Map<String, Object> wbsStat = new LinkedHashMap<>();
        wbsStat.put("total", nodes.size());
        wbsStat.put("phases", nodes.stream().filter(n -> n.getLevel() != null && n.getLevel() == PmWbs.LV_PHASE).count());
        wbsStat.put("packages", nodes.stream().filter(n -> n.getLevel() != null && n.getLevel() == PmWbs.LV_PACKAGE).count());
        wbsStat.put("tasks", nodes.stream().filter(n -> n.getLevel() != null && n.getLevel() == PmWbs.LV_TASK).count());
        out.put("wbsStat", wbsStat);
        // 预算行合计
        List<PmBudgetLine> lines = budgetLineDao.selectList(new LambdaQueryWrapper<PmBudgetLine>()
                .eq(PmBudgetLine::getProjectId, id)
                .eq(PmBudgetLine::getDelFlag, "0"));
        out.put("budgetLineCount", lines.size());
        return out;
    }

    @Override
    @Transactional
    public void delete(String id) {
        PmProject p = require(id);
        if (!PmProject.ST_DRAFT.equals(p.getStatus())) {
            throw new ServiceException(422, "仅草稿态项目可删除");
        }
        // @TableLogic 须走 deleteById（setDelFlag+updateById 会被 MP 忽略）
        for (PmWbs w : wbsDao.selectList(new LambdaQueryWrapper<PmWbs>().eq(PmWbs::getProjectId, id))) {
            wbsDao.deleteById(w.getId());
        }
        for (PmBudgetLine b : budgetLineDao.selectList(new LambdaQueryWrapper<PmBudgetLine>().eq(PmBudgetLine::getProjectId, id))) {
            budgetLineDao.deleteById(b.getId());
        }
        projectDao.deleteById(p.getId());
    }

    // ================================================================
    // helpers
    // ================================================================

    /** 应用 payload 并执行双路径门槛与项目经理校验 */
    private void applyPayload(PmProject p, Map<String, Object> payload) {
        String name = str(payload.get("name"));
        if (!hasText(name)) {
            throw new ServiceException(422, "项目名称必填");
        }
        p.setName(name);
        String projType = str(payload.get("projType"));
        if (!hasText(projType)) {
            throw new ServiceException(422, "项目类型必填");
        }
        p.setProjType(projType);
        String path = str(payload.get("deliveryPath"));
        if (!hasText(path)) {
            throw new ServiceException(422, "交付路径必填（EXT/INTERNAL）");
        }
        p.setDeliveryPath(path);

        // 双路径合同门槛（FR-4.8-2-1 L1 硬阻断）
        if (PmProject.PATH_EXT.equals(path)) {
            String contractId = str(payload.get("contractId"));
            if (!hasText(contractId)) {
                throw new ServiceException(422, "外部交付项目须关联合同");
            }
            Contract c = contractDao.selectById(contractId);
            if (c == null) {
                throw new ServiceException(422, "合同不存在或未签订，不可立项");
            }
            if (!Contract.ST_SIGNED.equals(c.getStatus())) {
                throw new ServiceException(422, "合同不存在或未签订，不可立项");
            }
            p.setContractId(contractId);
            p.setContractNo(c.getContractNo());
            p.setInternalFormNo(null);
        } else if (PmProject.PATH_INTERNAL.equals(path)) {
            String formNo = str(payload.get("internalFormNo"));
            if (!hasText(formNo)) {
                throw new ServiceException(422, "内部项目须填写内部立项审批单号");
            }
            p.setInternalFormNo(formNo);
            p.setContractId(null);
            p.setContractNo(null);
        } else {
            throw new ServiceException(422, "交付路径取值非法");
        }

        // 项目经理校验：存在且启用
        String pmUserId = str(payload.get("pmUserId"));
        if (!hasText(pmUserId)) {
            throw new ServiceException(422, "项目经理必填");
        }
        SysUser pm = userDao.selectById(pmUserId);
        if (pm == null || (pm.getStatus() != null && !"1".equals(pm.getStatus()))) {
            throw new ServiceException(422, "项目经理须为系统内已授权人员");
        }
        p.setPmUserId(pmUserId);
        p.setPmName(pm.getNickName() != null ? pm.getNickName() : pm.getUsername());

        if (payload.get("pmoUserId") != null) {
            p.setPmoUserId(str(payload.get("pmoUserId")));
        }
        String start = str(payload.get("startDate"));
        String end = str(payload.get("endDate"));
        if (!hasText(start) || !hasText(end)) {
            throw new ServiceException(422, "项目起止日期必填");
        }
        p.setStartDate(java.time.LocalDate.parse(start));
        p.setEndDate(java.time.LocalDate.parse(end));
        if (p.getEndDate().isBefore(p.getStartDate())) {
            throw new ServiceException(422, "结束日期不能早于开始日期");
        }
        if (payload.get("goal") != null) {
            p.setGoal(str(payload.get("goal")));
        }
        if (payload.get("scope") != null) {
            p.setScope(str(payload.get("scope")));
        }
        if (payload.get("fenplanRate") != null && hasText(str(payload.get("fenplanRate")))) {
            p.setFenplanRate(new BigDecimal(str(payload.get("fenplanRate"))));
        }
        if (payload.get("rateOverride") != null) {
            p.setRateOverride(str(payload.get("rateOverride")));
        }
    }

    private Map<String, Object> toRow(PmProject p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("projectNo", p.getProjectNo());
        m.put("name", p.getName());
        m.put("projType", p.getProjType());
        m.put("deliveryPath", p.getDeliveryPath());
        m.put("contractId", p.getContractId());
        m.put("contractNo", p.getContractNo());
        m.put("internalFormNo", p.getInternalFormNo());
        m.put("pmUserId", p.getPmUserId());
        m.put("pmName", p.getPmName());
        m.put("startDate", p.getStartDate());
        m.put("endDate", p.getEndDate());
        m.put("goal", p.getGoal());
        m.put("scope", p.getScope());
        m.put("status", p.getStatus());
        m.put("baselineLocked", p.getBaselineLocked());
        m.put("baselineAmount", p.getBaselineAmount());
        m.put("fenplanRate", p.getFenplanRate());
        m.put("rateOverride", p.getRateOverride());
        m.put("rejectOpinion", p.getRejectOpinion());
        m.put("apprId", p.getApprId());
        return m;
    }

    private PmProject require(String id) {
        if (!hasText(id)) {
            throw new ServiceException(422, "项目 ID 必填");
        }
        PmProject p = projectDao.selectById(id);
        if (p == null || "1".equals(p.getDelFlag())) {
            throw new ServiceException(404, "项目不存在");
        }
        return p;
    }

    private boolean hasAnyRole(String... wanted) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        List<String> roles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        for (String w : wanted) {
            for (String r : roles) {
                if (("ROLE_" + w).equalsIgnoreCase(r)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
