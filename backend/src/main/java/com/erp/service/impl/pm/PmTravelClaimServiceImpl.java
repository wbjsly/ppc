package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.pm.PmProjectDao;
import com.erp.dao.pm.PmTravelClaimDao;
import com.erp.dao.pm.PmTravelStandardDao;
import com.erp.dao.system.SysUserDao;
import com.erp.entity.pm.PmProject;
import com.erp.entity.pm.PmTravelClaim;
import com.erp.entity.pm.PmTravelStandard;
import com.erp.entity.system.SysUser;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.pm.PmCostCollectService;
import com.erp.service.pm.PmTravelClaimService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 差旅报销实现（add-cost-collection-freeze，FR-4.8-5-3）。
 * 超标（AMOUNT > DAYS × 职级标准）→ 强制填原因 + 提交时自动发起 L2 单节点财务审批；
 * 未挂项目 → DEPT_ONLY=1 归部门费用，不进项目成本；通过后归集差旅成本 + 凭证。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmTravelClaimServiceImpl implements PmTravelClaimService {

    /** 超标 L2 审批 BIZ_TYPE（PmTravelClaimApprovalCallback 对齐） */
    public static final String BIZ_TRAVEL_OVER = "PmTravelOver";

    private final PmTravelStandardDao stdDao;
    private final PmTravelClaimDao claimDao;
    private final PmProjectDao projectDao;
    private final SysUserDao userDao;
    private final ApprovalEngine approvalEngine;
    private final PmCostCollectService costCollectService;

    // ---- 标准 ----

    @Override
    public List<Map<String, Object>> listStandards() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PmTravelStandard s : stdDao.selectList(new LambdaQueryWrapper<PmTravelStandard>()
                .eq(PmTravelStandard::getDelFlag, "0")
                .eq(PmTravelStandard::getStatus, "1")
                .orderByAsc(PmTravelStandard::getGrade).orderByAsc(PmTravelStandard::getCityCategory))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.getId());
            row.put("grade", s.getGrade());
            row.put("cityCategory", s.getCityCategory());
            row.put("dailyLimit", s.getDailyLimit());
            out.add(row);
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> saveStandard(Map<String, Object> payload) {
        String grade = str(payload.get("grade"));
        String city = str(payload.get("cityCategory"));
        BigDecimal limit = dec(payload.get("dailyLimit"));
        if (!hasText(grade) || !hasText(city) || limit == null || limit.signum() <= 0) {
            throw new ServiceException(422, "职级/城市类别/日限额必填且限额为正");
        }
        PmTravelStandard exist = stdDao.selectOne(new LambdaQueryWrapper<PmTravelStandard>()
                .eq(PmTravelStandard::getGrade, grade)
                .eq(PmTravelStandard::getCityCategory, city)
                .eq(PmTravelStandard::getDelFlag, "0")
                .last("LIMIT 1"));
        PmTravelStandard s = exist != null ? exist : new PmTravelStandard();
        boolean isNew = exist == null;
        if (isNew) {
            s.setId(uuid());
            s.setStatus("1");
            s.setCreateBy(currentUser());
            s.setCreateDate(LocalDateTime.now());
        }
        s.setGrade(grade);
        s.setCityCategory(city);
        s.setDailyLimit(limit);
        if (isNew) {
            stdDao.insert(s);
        } else {
            s.setUpdateBy(currentUser());
            s.setUpdateDate(LocalDateTime.now());
            stdDao.updateById(s);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", s.getId());
        return out;
    }

    // ---- 报销单 ----

    @Override
    public List<Map<String, Object>> list(String projectId, String empId, String status) {
        LambdaQueryWrapper<PmTravelClaim> qw = new LambdaQueryWrapper<PmTravelClaim>()
                .eq(PmTravelClaim::getDelFlag, "0")
                .orderByDesc(PmTravelClaim::getCreateDate);
        if (hasText(projectId)) {
            qw.eq(PmTravelClaim::getProjectId, projectId);
        }
        if (hasText(empId)) {
            qw.eq(PmTravelClaim::getEmpId, empId);
        }
        if (hasText(status)) {
            qw.eq(PmTravelClaim::getStatus, status);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PmTravelClaim c : claimDao.selectList(qw)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("claim", c);
            out.add(row);
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> save(Map<String, Object> payload, boolean submit) {
        String me = currentUser();
        boolean isNew = true;
        PmTravelClaim c;
        String id = str(payload.get("id"));
        if (hasText(id)) {
            c = claimDao.selectById(id);
            if (c == null || "1".equals(c.getDelFlag())) {
                throw new ServiceException(422, "报销单不存在");
            }
            if (!PmTravelClaim.ST_DRAFT.equals(c.getStatus()) && !PmTravelClaim.ST_REJECTED.equals(c.getStatus())) {
                throw new ServiceException(422, "仅草稿/已驳回报销单可修改");
            }
            isNew = false;
        } else {
            c = new PmTravelClaim();
            c.setId(uuid());
            c.setClaimNo(nextNo());
            c.setEmpId(SecurityUtils.getCurrentUserId());
            SysUser u = userDao.selectById(c.getEmpId());
            c.setEmpName(u == null ? c.getEmpId() : (u.getNickName() != null ? u.getNickName() : u.getUsername()));
            c.setCreateBy(me);
            c.setCreateDate(LocalDateTime.now());
        }
        c.setGrade(str(payload.get("grade")));
        c.setProjectId(str(payload.get("projectId")));
        c.setWbsId(str(payload.get("wbsId")));
        c.setDeptId(str(payload.get("deptId")));
        c.setStartDate(LocalDate.parse(str(payload.get("startDate"))));
        c.setEndDate(LocalDate.parse(str(payload.get("endDate"))));
        if (c.getEndDate().isBefore(c.getStartDate())) {
            throw new ServiceException(422, "结束日期不可早于开始日期");
        }
        BigDecimal days = BigDecimal.valueOf(c.getEndDate().toEpochDay() - c.getStartDate().toEpochDay() + 1);
        c.setDays(days);
        BigDecimal amount = dec(payload.get("amount"));
        if (amount == null || amount.signum() <= 0) {
            throw new ServiceException(422, "报销金额须为正");
        }
        c.setAmount(amount);
        // 项目可空 → DEPT_ONLY
        if (!hasText(c.getProjectId())) {
            c.setDeptOnly("1");
        } else {
            c.setDeptOnly("0");
            PmProject p = projectDao.selectById(c.getProjectId());
            if (p == null) {
                throw new ServiceException(422, "项目不存在");
            }
            if (PmProject.FREEZE_YES.equals(p.getFreezeFlag())) {
                throw new ServiceException(422, "项目已冻结，不可新增差旅报销，请先走超预算审批解冻");
            }
        }
        // 职级标准快照与超标判定
        PmTravelStandard std = resolveStandard(c.getGrade());
        c.setDailyLimit(std == null ? null : std.getDailyLimit());
        boolean over = std != null && amount.compareTo(days.multiply(std.getDailyLimit())) > 0;
        c.setOverStandardFlag(over ? "1" : "0");
        String overReason = str(payload.get("overReason"));
        if (over && !hasText(overReason)) {
            throw new ServiceException(422, "超出差旅标准（" + days + " 天 × 日限额 "
                    + std.getDailyLimit() + "），必须填写超标原因");
        }
        c.setOverReason(over ? overReason : null);
        c.setStatus(PmTravelClaim.ST_DRAFT);
        if (isNew) {
            claimDao.insert(c);
        } else {
            c.setUpdateBy(me);
            c.setUpdateDate(LocalDateTime.now());
            claimDao.updateById(c);
        }
        if (submit) {
            if (over) {
                // 超标 → 自动发起 L2 单节点财务审批
                var inst = approvalEngine.submit(
                        BIZ_TRAVEL_OVER,
                        c.getId(),
                        "差旅超标审批：" + c.getEmpName() + " " + c.getStartDate() + " 至 " + c.getEndDate()
                                + "（" + c.getAmount() + " 元）",
                        null,
                        List.of(List.of(ApprovalNodeSpec.sign("ROLE_FINANCE", "财务超标审批"))));
                c.setOverApprId(inst.getId());
            }
            c.setStatus(PmTravelClaim.ST_SUBMITTED);
            c.setUpdateDate(LocalDateTime.now());
            claimDao.updateById(c);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", c.getId());
        out.put("claimNo", c.getClaimNo());
        out.put("status", c.getStatus());
        out.put("overStandard", c.getOverStandardFlag());
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> review(String id, Map<String, Object> payload) {
        PmTravelClaim c = claimDao.selectById(id);
        if (c == null || "1".equals(c.getDelFlag())) {
            throw new ServiceException(422, "报销单不存在");
        }
        if (!PmTravelClaim.ST_SUBMITTED.equals(c.getStatus())) {
            throw new ServiceException(422, "仅已提交报销单可审核");
        }
        if ("1".equals(c.getOverStandardFlag())) {
            throw new ServiceException(422, "超标报销单须走 L2 财务审批（勿常规审核）");
        }
        boolean approved = Boolean.parseBoolean(str(payload.get("approved")));
        String opinion = str(payload.get("opinion"));
        if (!approved && !hasText(opinion)) {
            throw new ServiceException(422, "驳回必须填写原因");
        }
        if (approved) {
            approveAndCollect(c);
            c.setStatus(PmTravelClaim.ST_APPROVED);
        } else {
            c.setStatus(PmTravelClaim.ST_REJECTED);
            c.setRejectReason(opinion);
        }
        c.setReviewBy(currentUser());
        c.setReviewDate(LocalDateTime.now());
        c.setUpdateDate(LocalDateTime.now());
        claimDao.updateById(c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", c.getId());
        out.put("status", c.getStatus());
        return out;
    }

    /** 通过后归集（常规审核与 L2 回调共用）：DEPT_ONLY 不归集项目成本 */
    static void approveAndCollect(PmTravelClaim c, PmCostCollectService costCollectService) {
        if ("1".equals(c.getDeptOnly())) {
            return; // 归部门费用，不进项目成本（FR-4.8-5-3）
        }
        costCollectService.collect(PmCostCollectService.TYPE_TRAVEL,
                PmCostCollectService.SRC_TRAVEL_CLAIM, c.getId(),
                c.getProjectId(), c.getWbsId(), c.getAmount(), LocalDate.now());
    }

    private void approveAndCollect(PmTravelClaim c) {
        approveAndCollect(c, costCollectService);
    }

    // ---- helpers ----

    /** 职级标准回落：精确职级 → MID → 无（null，L4 语义=不限额不判超标） */
    private PmTravelStandard resolveStandard(String grade) {
        if (!hasText(grade)) {
            return null;
        }
        // 取该职级各城市类别日限额的最大值作为出差地未知时的判定基准
        List<PmTravelStandard> rows = stdDao.selectList(new LambdaQueryWrapper<PmTravelStandard>()
                .eq(PmTravelStandard::getGrade, grade)
                .eq(PmTravelStandard::getStatus, "1")
                .eq(PmTravelStandard::getDelFlag, "0"));
        return rows.stream()
                .max((a, b) -> a.getDailyLimit().compareTo(b.getDailyLimit()))
                .orElse(null);
    }

    private String nextNo() {
        String prefix = "DTC" + LocalDate.now().toString().replace("-", "");
        Long n = claimDao.selectCount(new LambdaQueryWrapper<PmTravelClaim>()
                .likeRight(PmTravelClaim::getClaimNo, prefix));
        return prefix + String.format("%03d", (n == null ? 0 : n) + 1);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static BigDecimal dec(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return null;
        }
        return new BigDecimal(String.valueOf(o));
    }

    private static String currentUser() {
        try {
            return SecurityUtils.getCurrentUserId();
        } catch (Exception e) {
            return "system";
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
