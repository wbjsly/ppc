package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.pm.PmProjectDao;
import com.erp.dao.pm.PmTimesheetDao;
import com.erp.dao.pm.PmTimesheetLineDao;
import com.erp.dao.pm.PmWbsDao;
import com.erp.dao.system.SysUserDao;
import com.erp.entity.pm.PmProject;
import com.erp.entity.pm.PmTimesheet;
import com.erp.entity.pm.PmTimesheetLine;
import com.erp.entity.pm.PmWbs;
import com.erp.entity.system.SysUser;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.pm.PmHoursCollectService;
import com.erp.service.pm.PmTimesheetService;
import com.erp.service.system.NoticeService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 工时填报与审核实现（add-timesheet-management；design D2 状态机+补录分档，D3 归集挂钩）。
 * EMP_ID = 登录账号（与 PM 域 ownerUserId/pmUserId 同口径）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmTimesheetServiceImpl implements PmTimesheetService {

    /** 补录审批 BIZ_TYPE（PmTimesheetApprovalCallback 对齐） */
    public static final String BIZ_BACKFILL = "PmBackfill";

    /** L2 补录窗口下限：本周一往前 4 周（>4 周直接 422） */
    private static final int BACKFILL_L2_WEEKS = 4;

    private final PmTimesheetDao tsDao;
    private final PmTimesheetLineDao lineDao;
    private final PmWbsDao wbsDao;
    private final PmProjectDao projectDao;
    private final SysUserDao userDao;
    private final ApprovalEngine approvalEngine;
    private final NoticeService noticeService;
    private final PmHoursCollectService collectService;
    private final PmChangeInterceptor changeInterceptor;

    // ================================================================
    // 填报（FR-4.8-4-1 / BR-4.8-18）
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> save(Map<String, Object> payload) {
        String empId = SecurityUtils.getCurrentUserId();
        String workDateStr = str(payload.get("workDate"));
        if (!hasText(workDateStr)) {
            throw new ServiceException(422, "填报日期必填");
        }
        LocalDate workDate = LocalDate.parse(workDateStr);
        if (workDate.isAfter(LocalDate.now())) {
            throw new ServiceException(422, "不可填报未来日期");
        }
        Object rawLines = payload.get("lines");
        if (!(rawLines instanceof List<?> list) || list.isEmpty()) {
            throw new ServiceException(422, "明细行不能为空");
        }
        PmTimesheet sheet = null;
        boolean isNew = false;
        String id = str(payload.get("id"));
        if (hasText(id)) {
            sheet = tsDao.selectById(id);
            if (sheet == null || "1".equals(sheet.getDelFlag())) {
                throw new ServiceException(422, "工时单不存在");
            }
            if (!empId.equals(sheet.getEmpId())) {
                throw new ServiceException(403, "仅本人可编辑工时单");
            }
            if (!PmTimesheet.ST_DRAFT.equals(sheet.getStatus())
                    && !PmTimesheet.ST_REJECTED.equals(sheet.getStatus())) {
                throw new ServiceException(422, "仅草稿/已驳回工时单可修改");
            }
        } else {
            PmTimesheet existing = tsDao.selectOne(new LambdaQueryWrapper<PmTimesheet>()
                    .eq(PmTimesheet::getEmpId, empId)
                    .eq(PmTimesheet::getWorkDate, workDate)
                    .eq(PmTimesheet::getDelFlag, "0"));
            if (existing != null) {
                throw new ServiceException(409, "当日已有工时单（同人同天一单），请编辑既有单据");
            }
            sheet = new PmTimesheet();
            sheet.setId(uuid());
            sheet.setEmpId(empId);
            sheet.setWorkDate(workDate);
            sheet.setStatus(PmTimesheet.ST_DRAFT);
            sheet.setIsBackfill("0");
            sheet.setCreateBy(empId);
            sheet.setCreateDate(LocalDateTime.now());
            isNew = true;
        }
        SysUser u = userDao.selectById(empId);
        sheet.setEmpName(u == null ? empId : (u.getNickName() != null ? u.getNickName() : u.getUsername()));
        // 冻结拦截（BR-4.8-21：冻结范围含工时录入；add-cost-collection-freeze）
        for (Object rawLine : list) {
            if (rawLine instanceof Map<?, ?> m) {
                String projId = m.get("projectId") == null ? null : String.valueOf(m.get("projectId"));
                if (projId != null && !projId.isBlank()) {
                    PmProject proj = projectDao.selectById(projId);
                    if (proj != null && PmProject.FREEZE_YES.equals(proj.getFreezeFlag())) {
                        throw new ServiceException(422, "项目「" + proj.getName()
                                + "」已超支冻结，不可录入工时，请联系 PMO 走超预算审批解冻");
                    }
                }
            }
        }
        // 替换式保存明细行（旧逻辑删，新插入）
        for (PmTimesheetLine old : lineDao.selectList(new LambdaQueryWrapper<PmTimesheetLine>()
                .eq(PmTimesheetLine::getTimesheetId, sheet.getId())
                .eq(PmTimesheetLine::getDelFlag, "0"))) {
            lineDao.deleteById(old.getId());
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> row)) {
                continue;
            }
            String wbsId = str(row.get("wbsId"));
            PmWbs task = requireTask(wbsId);
            // 变更拦截（C-4.8-09，add-project-change）：按 WBS 精准拦，不误伤其他项目/任务
            changeInterceptor.assertNoPendingChange(task.getProjectId(), wbsId);
            BigDecimal hours = dec(row.get("hours"));
            checkHalfStep(hours);
            PmTimesheetLine line = new PmTimesheetLine();
            line.setId(uuid());
            line.setTimesheetId(sheet.getId());
            line.setProjectId(task.getProjectId()); // 以任务所属项目为准
            line.setWbsId(wbsId);
            line.setHours(hours);
            line.setWorkContent(str(row.get("workContent")));
            if (!hasText(line.getWorkContent())) {
                throw new ServiceException(422, "工作内容摘要必填");
            }
            line.setCreateBy(empId);
            line.setCreateDate(LocalDateTime.now());
            lineDao.insert(line);
            total = total.add(hours);
        }
        if (total.compareTo(new BigDecimal("24")) > 0) {
            throw new ServiceException(422, "当日合计 " + total + " 小时超上限 24（FR-4.8-4-1 L1）");
        }
        sheet.setTotalHours(total);
        sheet.setUpdateBy(empId);
        sheet.setUpdateDate(LocalDateTime.now());
        if (isNew) {
            tsDao.insert(sheet);
        } else {
            tsDao.updateById(sheet);
        }
        return toRow(sheet, true);
    }

    @Override
    public List<Map<String, Object>> list(String empId, String dateFrom, String dateTo) {
        String me = SecurityUtils.getCurrentUserId();
        boolean managerView = hasAnyRole("PMO", "ADMIN", "PM", "DEPT_HEAD");
        String target = hasText(empId) ? empId : me;
        if (!managerView && !target.equals(me)) {
            throw new ServiceException(403, "仅可查看本人工时");
        }
        LambdaQueryWrapper<PmTimesheet> qw = new LambdaQueryWrapper<PmTimesheet>()
                .eq(PmTimesheet::getDelFlag, "0")
                .eq(PmTimesheet::getEmpId, target)
                .orderByDesc(PmTimesheet::getWorkDate);
        if (hasText(dateFrom)) {
            qw.ge(PmTimesheet::getWorkDate, LocalDate.parse(dateFrom));
        }
        if (hasText(dateTo)) {
            qw.le(PmTimesheet::getWorkDate, LocalDate.parse(dateTo));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PmTimesheet s : tsDao.selectList(qw)) {
            out.add(toRow(s, true));
        }
        return out;
    }

    @Override
    public Map<String, Object> myToday() {
        String me = SecurityUtils.getCurrentUserId();
        PmTimesheet s = tsDao.selectOne(new LambdaQueryWrapper<PmTimesheet>()
                .eq(PmTimesheet::getEmpId, me)
                .eq(PmTimesheet::getWorkDate, LocalDate.now())
                .eq(PmTimesheet::getDelFlag, "0"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exists", s != null);
        out.put("sheet", s == null ? null : toRow(s, true));
        return out;
    }

    // ================================================================
    // 提交（三道 L1 + 补录分档，BR-4.8-17/18）
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> submit(String id) {
        PmTimesheet sheet = require(id);
        String me = SecurityUtils.getCurrentUserId();
        if (!sheet.getEmpId().equals(me)) {
            throw new ServiceException(403, "仅本人可提交工时单");
        }
        List<PmTimesheetLine> lines = activeLines(sheet.getId());
        if (lines.isEmpty()) {
            throw new ServiceException(422, "明细行为空，不可提交");
        }
        // L1①：任务级校验（BR-4.8-18，save 时已校验，此处防御性复核）
        BigDecimal total = BigDecimal.ZERO;
        for (PmTimesheetLine line : lines) {
            PmWbs task = requireTask(line.getWbsId());
            if (task.getLevel() == null || task.getLevel() != PmWbs.LV_TASK) {
                throw new ServiceException(422, "存在未关联 WBS 任务的明细（BR-4.8-18）");
            }
            total = total.add(line.getHours());
        }
        // L1②：24h 合计
        if (total.compareTo(new BigDecimal("24")) > 0) {
            throw new ServiceException(422, "当日合计超 24 小时（FR-4.8-4-1 L1）");
        }
        // L1③：补录分档（BR-4.8-17 两档 + 更早 422）
        LocalDate thisMonday = LocalDate.now().with(DayOfWeek.MONDAY);
        LocalDate lastMonday = thisMonday.minusWeeks(1);
        LocalDate l2Floor = thisMonday.minusWeeks(BACKFILL_L2_WEEKS);
        boolean backfillFlag = sheet.getWorkDate().isBefore(thisMonday);
        if (sheet.getWorkDate().isBefore(l2Floor)) {
            throw new ServiceException(422,
                    "工时日期早于 4 周窗口（BR-4.8-17），不可补录：" + sheet.getWorkDate());
        }
        if (sheet.getWorkDate().isBefore(lastMonday)) {
            // 超 2 周（2~4 周前）：自动发起单节点 PMO 补录审批（L2 强制）
            transition(sheet, PmTimesheet.ST_BACKFILL, "SUBMIT_BACKFILL");
            sheet.setIsBackfill("1");
            var inst = approvalEngine.submit(
                    BIZ_BACKFILL,
                    sheet.getId(),
                    "工时补录审批：" + sheet.getEmpName() + " " + sheet.getWorkDate(),
                    null,
                    List.of(List.of(ApprovalNodeSpec.sign("ROLE_PMO", "PMO 补录审批"))));
            sheet.setBackfillApprId(inst.getId());
            sheet.setSubmitDate(LocalDateTime.now());
            sheet.setUpdateBy(me);
            sheet.setUpdateDate(LocalDateTime.now());
            tsDao.updateById(sheet);
            Map<String, Object> out = toRow(sheet, true);
            out.put("hint", "超 2 周补录已提交 PMO 强制审批（L2），通过后进入审核队列");
            return out;
        }
        // 窗口内：正常提交（上周补录打标）
        transition(sheet, PmTimesheet.ST_SUBMITTED, "SUBMIT");
        if (backfillFlag) {
            sheet.setIsBackfill("1");
        }
        sheet.setSubmitDate(LocalDateTime.now());
        sheet.setUpdateBy(me);
        sheet.setUpdateDate(LocalDateTime.now());
        tsDao.updateById(sheet);
        return toRow(sheet, true);
    }

    // ================================================================
    // 审核（FR-4.8-4-2：单人任务负责人/跨人或无负责人回落项目经理）
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> review(String id, Map<String, Object> payload) {
        PmTimesheet sheet = require(id);
        if (!PmTimesheet.ST_SUBMITTED.equals(sheet.getStatus())) {
            throw new ServiceException(422, "仅待审核工时单可审核（当前 " + sheet.getStatus() + "）");
        }
        requireReviewerAuthority(sheet);
        String action = str(payload.get("action"));
        String me = SecurityUtils.getCurrentUserId();
        if ("REJECT".equalsIgnoreCase(action)) {
            String reason = str(payload.get("reason"));
            if (!hasText(reason) || reason.length() < 2) {
                throw new ServiceException(422, "驳回原因必填（至少 2 字）");
            }
            transition(sheet, PmTimesheet.ST_REJECTED, "REJECT");
            sheet.setRejectReason(reason);
        } else {
            transition(sheet, PmTimesheet.ST_APPROVED, "APPROVE");
        }
        sheet.setReviewBy(me);
        sheet.setReviewDate(LocalDateTime.now());
        sheet.setUpdateBy(me);
        sheet.setUpdateDate(LocalDateTime.now());
        tsDao.updateById(sheet);
        Map<String, Object> out = toRow(sheet, true);
        if (PmTimesheet.ST_APPROVED.equals(sheet.getStatus())) {
            // 审批通过即归集（D3，事务内）
            int collected = collectService.collect(sheet);
            out.put("collected", collected);
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> bulkApprove(List<String> ids) {
        int ok = 0;
        List<String> failed = new ArrayList<>();
        for (String id : ids) {
            try {
                review(id, Map.of("action", "APPROVE"));
                ok++;
            } catch (Exception e) {
                failed.add(id + ":" + e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("approved", ok);
        out.put("failed", failed);
        return out;
    }

    @Override
    public List<Map<String, Object>> pendingReview() {
        String me = SecurityUtils.getCurrentUserId();
        boolean managerAll = hasAnyRole("PMO", "ADMIN", "DEPT_HEAD");
        List<Map<String, Object>> out = new ArrayList<>();
        for (PmTimesheet s : tsDao.selectList(new LambdaQueryWrapper<PmTimesheet>()
                .eq(PmTimesheet::getStatus, PmTimesheet.ST_SUBMITTED)
                .eq(PmTimesheet::getDelFlag, "0")
                .orderByAsc(PmTimesheet::getSubmitDate))) {
            String reviewer = resolveReviewer(s);
            if (managerAll || me.equals(reviewer)) {
                Map<String, Object> row = toRow(s, true);
                row.put("reviewerUserId", reviewer);
                out.add(row);
            }
        }
        return out;
    }

    /** 审核人解析：明细任务负责人去重恰 1 人 → 该人；否则项目经理（首行项目） */
    private String resolveReviewer(PmTimesheet sheet) {
        Set<String> owners = new LinkedHashSet<>();
        String firstProjectId = null;
        for (PmTimesheetLine line : activeLines(sheet.getId())) {
            if (firstProjectId == null) {
                firstProjectId = line.getProjectId();
            }
            PmWbs task = wbsDao.selectById(line.getWbsId());
            if (task != null && hasText(task.getOwnerUserId())) {
                owners.add(task.getOwnerUserId());
            }
        }
        if (owners.size() == 1) {
            return owners.iterator().next();
        }
        PmProject project = firstProjectId == null ? null : projectDao.selectById(firstProjectId);
        return project == null ? null : project.getPmUserId();
    }

    private void requireReviewerAuthority(PmTimesheet sheet) {
        if (hasAnyRole("PMO", "ADMIN", "DEPT_HEAD")) {
            return;
        }
        String reviewer = resolveReviewer(sheet);
        String me = SecurityUtils.getCurrentUserId();
        if (reviewer == null || !me.equals(reviewer)) {
            throw new ServiceException(403, "当前用户非该工时单审核人");
        }
    }

    // ================================================================
    // 回调专用（PmTimesheetApprovalCallback 调用）
    // ================================================================

    /** BACKFILL → SUBMITTED（补录审批通过，进入审核队列） */
    public static void backfillApproved(PmTimesheet sheet) {
        if (!PmTimesheet.ST_BACKFILL.equals(sheet.getStatus())) {
            throw new ServiceException(422, "仅 BACKFILL 态可过补录审批（当前 " + sheet.getStatus() + "）");
        }
        sheet.setStatus(PmTimesheet.ST_SUBMITTED);
    }

    /** BACKFILL → DRAFT（补录审批驳回） */
    public static void backfillRejected(PmTimesheet sheet) {
        sheet.setStatus(PmTimesheet.ST_DRAFT);
    }

    // ================================================================
    // helpers
    // ================================================================

    /** 状态机单点：非法转移 422 */
    private void transition(PmTimesheet sheet, String to, String action) {
        String from = sheet.getStatus();
        boolean legal =
                (PmTimesheet.ST_DRAFT.equals(from) && PmTimesheet.ST_SUBMITTED.equals(to))
                || (PmTimesheet.ST_REJECTED.equals(from) && PmTimesheet.ST_SUBMITTED.equals(to))
                || (PmTimesheet.ST_DRAFT.equals(from) && PmTimesheet.ST_BACKFILL.equals(to))
                || (PmTimesheet.ST_REJECTED.equals(from) && PmTimesheet.ST_BACKFILL.equals(to))
                || (PmTimesheet.ST_SUBMITTED.equals(from) && PmTimesheet.ST_APPROVED.equals(to))
                || (PmTimesheet.ST_SUBMITTED.equals(from) && PmTimesheet.ST_REJECTED.equals(to));
        if (!legal) {
            throw new ServiceException(422, "非法状态转移：" + from + " → " + to + "（" + action + "）");
        }
        sheet.setStatus(to);
    }

    private List<PmTimesheetLine> activeLines(String timesheetId) {
        return lineDao.selectList(new LambdaQueryWrapper<PmTimesheetLine>()
                .eq(PmTimesheetLine::getTimesheetId, timesheetId)
                .eq(PmTimesheetLine::getDelFlag, "0"));
    }

    /** 任务必须存在且为任务级（level=3） */
    private PmWbs requireTask(String wbsId) {
        if (!hasText(wbsId)) {
            throw new ServiceException(422, "明细须关联 WBS 任务（BR-4.8-18）");
        }
        PmWbs task = wbsDao.selectById(wbsId);
        if (task == null || "1".equals(task.getDelFlag())) {
            throw new ServiceException(422, "WBS 任务不存在");
        }
        if (task.getLevel() == null || task.getLevel() != PmWbs.LV_TASK) {
            throw new ServiceException(422, "仅任务级（level=3）WBS 节点可报工时");
        }
        return task;
    }

    private static void checkHalfStep(BigDecimal hours) {
        if (hours == null || hours.signum() <= 0) {
            throw new ServiceException(422, "工时必须大于 0");
        }
        if (hours.remainder(new BigDecimal("0.5")).compareTo(BigDecimal.ZERO) != 0) {
            throw new ServiceException(422, "工时须为 0.5 的整数倍（FR-4.8-4-1）");
        }
    }

    private Map<String, Object> toRow(PmTimesheet s, boolean withLines) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", s.getId());
        row.put("workDate", s.getWorkDate() == null ? null : s.getWorkDate().toString());
        row.put("empId", s.getEmpId());
        row.put("empName", s.getEmpName());
        row.put("status", s.getStatus());
        row.put("totalHours", s.getTotalHours());
        row.put("isBackfill", s.getIsBackfill());
        row.put("backfillApprId", s.getBackfillApprId());
        row.put("submitDate", s.getSubmitDate() == null ? null : s.getSubmitDate().toString());
        row.put("reviewBy", s.getReviewBy());
        row.put("reviewDate", s.getReviewDate() == null ? null : s.getReviewDate().toString());
        row.put("rejectReason", s.getRejectReason());
        if (withLines) {
            List<Map<String, Object>> lines = new ArrayList<>();
            for (PmTimesheetLine l : activeLines(s.getId())) {
                Map<String, Object> lr = new LinkedHashMap<>();
                lr.put("id", l.getId());
                lr.put("projectId", l.getProjectId());
                lr.put("wbsId", l.getWbsId());
                PmWbs task = wbsDao.selectById(l.getWbsId());
                lr.put("wbsName", task == null ? null : task.getName());
                lr.put("hours", l.getHours());
                lr.put("workContent", l.getWorkContent());
                lines.add(lr);
            }
            row.put("lines", lines);
        }
        return row;
    }

    private PmTimesheet require(String id) {
        if (!hasText(id)) {
            throw new ServiceException(422, "工时单 ID 必填");
        }
        PmTimesheet s = tsDao.selectById(id);
        if (s == null || "1".equals(s.getDelFlag())) {
            throw new ServiceException(422, "工时单不存在");
        }
        return s;
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
        return o == null ? null : String.valueOf(o).trim();
    }

    private static BigDecimal dec(Object o) {
        try {
            return new BigDecimal(String.valueOf(o).trim()).setScale(1, RoundingMode.HALF_UP);
        } catch (Exception e) {
            throw new ServiceException(422, "工时格式非法");
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
