package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.FinVoucherDao;
import com.erp.dao.hr.HrDeptDao;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrPayAccountMappingDao;
import com.erp.dao.hr.HrRemindDao;
import com.erp.dao.hr.HrTrainPlanDao;
import com.erp.dao.hr.HrTrainTaskDao;
import com.erp.entity.fin.FinVoucher;
import com.erp.entity.hr.HrDept;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrPayAccountMapping;
import com.erp.entity.hr.HrRemind;
import com.erp.entity.hr.HrTrainPlan;
import com.erp.entity.hr.HrTrainTask;
import com.erp.service.SysParamService;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.hr.HrSkillMatrixService;
import com.erp.service.hr.HrTrainPlanService;
import com.erp.service.system.NoticeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 年度培训计划实现（training-plan-execution）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrTrainPlanServiceImpl implements HrTrainPlanService {

    private final HrTrainPlanDao planDao;
    private final HrTrainTaskDao taskDao;
    private final HrEmployeeDao empDao;
    private final HrDeptDao deptDao;
    private final HrRemindDao remindDao;
    private final HrPayAccountMappingDao mappingDao;
    private final FinVoucherDao voucherDao;
    private final HrSkillMatrixService matrixService;
    private final GlVoucherService voucherService;
    private final NoticeService noticeService;
    private final SysParamService sysParamService;

    public static final String SRC_TRAINING = "TRAINING";

    // ================================================================
    // 计划 CRUD
    // ================================================================

    @Override
    public List<Map<String, Object>> list(Integer year) {
        LambdaQueryWrapper<HrTrainPlan> qw = new LambdaQueryWrapper<HrTrainPlan>()
                .eq(HrTrainPlan::getDelFlag, "0")
                .orderByDesc(HrTrainPlan::getPlanYear)
                .orderByDesc(HrTrainPlan::getPlanNo);
        if (year != null) {
            qw.eq(HrTrainPlan::getPlanYear, year);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (HrTrainPlan p : planDao.selectList(qw)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("plan", p);
            row.put("stats", taskStats(p.getId()));
            out.add(row);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(Map<String, Object> payload) {
        String courseName = str(payload.get("courseName"));
        require(courseName, "课程名称");
        String targetType = str(payload.get("targetType"));
        String targetValue = str(payload.get("targetValue"));
        require(targetType, "培训对象类型");
        require(targetValue, "培训对象");
        if (!HrTrainPlan.TARGET_DEPT.equals(targetType) && !HrTrainPlan.TARGET_MEMBERS.equals(targetType)) {
            throw new ServiceException(422, "对象类型非法（DEPT/MEMBERS）");
        }
        Integer year = intOrNull(payload.get("planYear"));
        if (year == null) {
            year = LocalDate.now().getYear();
        }
        HrTrainPlan p = new HrTrainPlan();
        p.setId(uuid());
        p.setPlanNo(nextNo(year));
        p.setPlanYear(year);
        p.setCourseName(courseName);
        p.setTrainType(str(payload.get("trainType")));
        p.setSkillCode(str(payload.get("skillCode")));
        p.setSkillLevel(str(payload.get("skillLevel")));
        p.setTargetType(targetType);
        p.setTargetValue(targetValue);
        p.setDateFrom(date(payload.get("dateFrom")));
        p.setDateTo(date(payload.get("dateTo")));
        p.setDueDate(date(payload.get("dueDate")));
        p.setPlanHours(dec(payload.get("planHours")));
        Integer pass = intOrNull(payload.get("passScore"));
        p.setPassScore(pass == null ? sysParamService.getInt("TRAIN_PASS_SCORE", 60) : pass);
        p.setBudget(dec(payload.get("budget")));
        p.setOwner(str(payload.get("owner")) == null ? currentUser() : str(payload.get("owner")));
        p.setStatus(HrTrainPlan.ST_PUBLISHED);
        p.setCreateBy(currentUser());
        planDao.insert(p);
        int expanded = expandTasks(p);
        log.info("train plan created: {} {} expanded={}", p.getCourseName(), p.getPlanNo(), expanded);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", p);
        out.put("expanded", expanded);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> update(String id, Map<String, Object> payload) {
        HrTrainPlan p = require(id);
        if (HrTrainPlan.ST_CANCELLED.equals(p.getStatus())) {
            throw new ServiceException(422, "已作废计划不可修改");
        }
        StringBuilder logJson = p.getChangeLogJson() == null ? new StringBuilder("[]") : new StringBuilder(p.getChangeLogJson());
        int expanded = 0;
        if (payload.containsKey("courseName")) {
            logJson = appendLog(logJson, "courseName", p.getCourseName(), str(payload.get("courseName")));
            p.setCourseName(str(payload.get("courseName")));
        }
        if (payload.containsKey("trainType")) {
            logJson = appendLog(logJson, "trainType", p.getTrainType(), str(payload.get("trainType")));
            p.setTrainType(str(payload.get("trainType")));
        }
        if (payload.containsKey("skillCode")) {
            logJson = appendLog(logJson, "skillCode", p.getSkillCode(), str(payload.get("skillCode")));
            p.setSkillCode(str(payload.get("skillCode")));
        }
        if (payload.containsKey("skillLevel")) {
            logJson = appendLog(logJson, "skillLevel", p.getSkillLevel(), str(payload.get("skillLevel")));
            p.setSkillLevel(str(payload.get("skillLevel")));
        }
        if (payload.containsKey("dateFrom")) {
            logJson = appendLog(logJson, "dateFrom", str(p.getDateFrom()), str(payload.get("dateFrom")));
            p.setDateFrom(date(payload.get("dateFrom")));
        }
        if (payload.containsKey("dateTo")) {
            logJson = appendLog(logJson, "dateTo", str(p.getDateTo()), str(payload.get("dateTo")));
            p.setDateTo(date(payload.get("dateTo")));
        }
        if (payload.containsKey("dueDate")) {
            logJson = appendLog(logJson, "dueDate", str(p.getDueDate()), str(payload.get("dueDate")));
            p.setDueDate(date(payload.get("dueDate")));
        }
        if (payload.containsKey("planHours")) {
            logJson = appendLog(logJson, "planHours", str(p.getPlanHours()), str(payload.get("planHours")));
            p.setPlanHours(dec(payload.get("planHours")));
        }
        if (payload.containsKey("passScore")) {
            logJson = appendLog(logJson, "passScore", str(p.getPassScore()), str(payload.get("passScore")));
            p.setPassScore(intOrNull(payload.get("passScore")));
        }
        if (payload.containsKey("budget")) {
            logJson = appendLog(logJson, "budget", str(p.getBudget()), str(payload.get("budget")));
            p.setBudget(dec(payload.get("budget")));
        }
        if (payload.containsKey("owner")) {
            logJson = appendLog(logJson, "owner", p.getOwner(), str(payload.get("owner")));
            p.setOwner(str(payload.get("owner")));
        }
        if (payload.containsKey("targetValue")) {
            String tv = str(payload.get("targetValue"));
            require(tv, "培训对象");
            logJson = appendLog(logJson, "targetValue", p.getTargetValue(), tv);
            p.setTargetValue(tv);
            if (payload.containsKey("targetType")) {
                p.setTargetType(str(payload.get("targetType")));
            }
            expanded = expandTasks(p); // 只补新任务（UK 幂等），不删旧任务
        }
        p.setChangeLogJson(logJson.toString());
        p.setUpdateBy(currentUser());
        p.setUpdateDate(LocalDateTime.now());
        planDao.updateById(p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", p);
        out.put("expanded", expanded);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(String id) {
        HrTrainPlan p = require(id);
        if (HrTrainPlan.ST_CANCELLED.equals(p.getStatus())) {
            return;
        }
        p.setStatus(HrTrainPlan.ST_CANCELLED);
        touchPlan(p);
        for (HrTrainTask t : taskDao.selectList(new LambdaQueryWrapper<HrTrainTask>()
                .eq(HrTrainTask::getPlanId, id)
                .in(HrTrainTask::getStatus, HrTrainTask.ST_PENDING, HrTrainTask.ST_CONFIRMED))) {
            t.setStatus(HrTrainTask.ST_CANCELLED);
            touchTask(t);
        }
        // 已生成凭证红字冲销（spec：计划作废冲销）
        int reversed = reverseActiveVouchers(anchor(p), "计划作废冲销");
        log.info("train plan cancelled: {} vouchersReversed={}", p.getPlanNo(), reversed);
    }

    @Override
    public Map<String, Object> detail(String id) {
        HrTrainPlan p = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", p);
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (HrTrainTask t : taskDao.selectList(new LambdaQueryWrapper<HrTrainTask>()
                .eq(HrTrainTask::getPlanId, id)
                .orderByAsc(HrTrainTask::getEmpNo))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("task", t);
            HrEmployee e = empDao.selectById(t.getEmpId());
            row.put("deptId", e == null ? t.getDeptId() : e.getDeptId());
            tasks.add(row);
        }
        out.put("tasks", tasks);
        out.put("stats", taskStats(id));
        out.put("vouchers", voucherService.listBySource(SRC_TRAINING, anchor(p)));
        return out;
    }

    // ================================================================
    // 结果登记与完成判定
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> registerResult(String taskId, Map<String, Object> payload) {
        HrTrainTask t = requireTask(taskId);
        HrTrainPlan p = require(t.getPlanId());
        if (HrTrainPlan.ST_CANCELLED.equals(p.getStatus())) {
            throw new ServiceException(422, "计划已作废，不可登记结果");
        }
        String prevStatus = t.getStatus();
        BigDecimal prevScore = t.getScore();
        t.setAttend(str(payload.get("attend")));
        BigDecimal score = dec(payload.get("score"));
        requireScore(score, "考试成绩");
        t.setScore(score);
        t.setActualHours(dec(payload.get("actualHours")));
        t.setRemark(str(payload.get("remark")));
        int passScore = p.getPassScore() == null ? sysParamService.getInt("TRAIN_PASS_SCORE", 60) : p.getPassScore();
        String newStatus = score.compareTo(BigDecimal.valueOf(passScore)) >= 0
                ? HrTrainTask.ST_COMPLETED : HrTrainTask.ST_FAILED;
        t.setStatus(newStatus);
        t.setResultBy(currentUser());
        t.setResultAt(LocalDateTime.now());
        t.setResultHistJson(appendHist(t.getResultHistJson(), prevScore, score, prevStatus, newStatus));
        touchTask(t);
        // 技能矩阵联动：完成写入；COMPLETED 改判不达线 → 仅 TRAIN 来源行失效
        if (p.getSkillCode() != null && !p.getSkillCode().isBlank()) {
            if (HrTrainTask.ST_COMPLETED.equals(newStatus)) {
                matrixService.syncFromTraining(t.getEmpId(), p.getSkillCode(), p.getSkillLevel(), p.getId());
            } else if (HrTrainTask.ST_COMPLETED.equals(prevStatus)) {
                matrixService.expireFromTraining(t.getEmpId(), p.getSkillCode(), p.getId());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", t);
        out.put("passScore", passScore);
        return out;
    }

    // ================================================================
    // 费用与计提凭证
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> registerCost(String planId, Map<String, Object> payload) {
        HrTrainPlan p = require(planId);
        if (HrTrainPlan.ST_CANCELLED.equals(p.getStatus())) {
            throw new ServiceException(422, "计划已作废，不可登记费用");
        }
        BigDecimal cost = dec(payload.get("actualCost"));
        if (cost == null || cost.signum() < 0) {
            throw new ServiceException(422, "实际费用非法（须 ≥0）");
        }
        p.setActualCost(cost);
        touchPlan(p);
        // 科目缺失 → L1 拒绝（fail fast，不静默）
        String expenseAcc = fixedAccount("TRAIN_EXPENSE");
        String payableAcc = fixedAccount("TRAIN_PAYABLE");
        String anc = anchor(p);
        List<FinVoucher> active = activeVouchers(anc);
        BigDecimal activeSum = activeSum(active);
        int action = 0; // 0 无 1 新建 2 红冲重开 3 清零冲销
        if (cost.signum() == 0) {
            action = reverseActiveVouchers(anc, "培训费用清零冲销") > 0 ? 3 : 0;
        } else if (active.isEmpty()) {
            createVoucher(p, anc, cost, expenseAcc, payableAcc);
            action = 1;
        } else if (activeSum.compareTo(cost) != 0) {
            reverseActiveVouchers(anc, "培训费用调整冲销重开");
            createVoucher(p, anc, cost, expenseAcc, payableAcc);
            action = 2;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planNo", p.getPlanNo());
        out.put("actualCost", cost);
        out.put("anchor", anc);
        out.put("action", action); // 1 新建 / 2 红冲重开 / 3 清零冲销 / 0 无变化
        out.put("vouchers", voucherService.listBySource(SRC_TRAINING, anc));
        return out;
    }

    // ================================================================
    // 完成率
    // ================================================================

    @Override
    public List<Map<String, Object>> rates(Integer year, String deptId) {
        Map<String, int[]> byDept = new java.util.LinkedHashMap<>(); // deptId -> [should, completed]
        for (HrTrainTask t : taskDao.selectList(new LambdaQueryWrapper<HrTrainTask>()
                .eq(HrTrainTask::getDelFlag, "0"))) {
            HrTrainPlan p = planDao.selectById(t.getPlanId());
            if (p == null || !HrTrainPlan.ST_PUBLISHED.equals(p.getStatus())) {
                continue;
            }
            if (year != null && !year.equals(p.getPlanYear())) {
                continue;
            }
            String d = t.getDeptId();
            if (d == null) {
                HrEmployee e = empDao.selectById(t.getEmpId());
                d = e == null ? "-" : e.getDeptId();
            }
            if (deptId != null && !deptId.isBlank() && !deptId.equals(d)) {
                continue;
            }
            if (HrTrainTask.ST_CANCELLED.equals(t.getStatus())) {
                continue; // CANCELLED 不计分母
            }
            int[] cnt = byDept.computeIfAbsent(d, k -> new int[2]);
            cnt[0]++;
            if (HrTrainTask.ST_COMPLETED.equals(t.getStatus())) {
                cnt[1]++;
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : byDept.entrySet()) {
            HrDept dept = deptDao.selectById(e.getKey());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("deptId", e.getKey());
            row.put("deptName", dept == null ? null : dept.getName());
            row.put("should", e.getValue()[0]);
            row.put("completed", e.getValue()[1]);
            row.put("rate", e.getValue()[0] == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(e.getValue()[1] * 100L).divide(BigDecimal.valueOf(e.getValue()[0]), 2,
                            java.math.RoundingMode.HALF_UP));
            out.add(row);
        }
        return out;
    }

    // ================================================================
    // 催办
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> remindScan() {
        int remindDays = sysParamService.getInt("TRAIN_TASK_REMIND_DAYS", 3);
        LocalDate today = LocalDate.now();
        int reminded = 0;
        int overdue = 0;
        for (HrTrainTask t : taskDao.selectList(new LambdaQueryWrapper<HrTrainTask>()
                .eq(HrTrainTask::getDelFlag, "0")
                .in(HrTrainTask::getStatus, HrTrainTask.ST_PENDING, HrTrainTask.ST_CONFIRMED))) {
            HrTrainPlan p = planDao.selectById(t.getPlanId());
            if (p == null || p.getDueDate() == null || !HrTrainPlan.ST_PUBLISHED.equals(p.getStatus())) {
                continue;
            }
            long left = ChronoUnit.DAYS.between(today, p.getDueDate());
            boolean isOverdue = left < 0;
            if (!isOverdue && (left > remindDays)) {
                continue;
            }
            String detail = (isOverdue
                    ? "培训任务已逾期 " + (-left) + " 天未完成："
                    : "培训任务距完成期限剩 " + left + " 天：")
                    + p.getCourseName() + "（计划 " + p.getPlanNo() + "，期限 " + p.getDueDate() + "），请尽快完成";
            try {
                HrRemind r = new HrRemind();
                r.setId(uuid());
                r.setRemindType(HrRemind.TYPE_TRAIN_TASK);
                r.setEmpId(t.getEmpId());
                r.setEmpName(t.getEmpName());
                r.setRemindDate(today);
                r.setDetail(detail);
                r.setCreateBy("system");
                remindDao.insert(r);
            } catch (Exception ignored) {
                // 同日已提醒（UK）跳过
            }
            HrEmployee e = empDao.selectById(t.getEmpId());
            try {
                if (e != null && e.getUserId() != null && !e.getUserId().isBlank()) {
                    noticeService.push(null, e.getUserId(), "我的培训任务提醒", detail, "TRAIN_TASK", t.getId());
                }
                if (p.getOwner() != null && !p.getOwner().isBlank()) {
                    noticeService.push(null, p.getOwner(), "培训任务催办-" + p.getCourseName(), detail,
                            "TRAIN_TASK", t.getId());
                }
            } catch (Exception ex) {
                log.warn("train remind push failed {}: {}", t.getId(), ex.getMessage());
            }
            if (isOverdue) {
                overdue++;
            } else {
                reminded++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reminded", reminded);
        out.put("overdue", overdue);
        return out;
    }

    // ================================================================
    // 自助端
    // ================================================================

    @Override
    public List<Map<String, Object>> myTasks(String empId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (HrTrainTask t : taskDao.selectList(new LambdaQueryWrapper<HrTrainTask>()
                .eq(HrTrainTask::getEmpId, empId)
                .eq(HrTrainTask::getDelFlag, "0")
                .orderByDesc(HrTrainTask::getCreateDate))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("task", t);
            row.put("plan", planDao.selectById(t.getPlanId()));
            out.add(row);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirmTask(String taskId, String empId) {
        HrTrainTask t = requireTask(taskId);
        if (!empId.equals(t.getEmpId())) {
            throw new ServiceException(403, "非本人培训任务");
        }
        if (HrTrainTask.ST_PENDING.equals(t.getStatus())) {
            t.setStatus(HrTrainTask.ST_CONFIRMED);
            t.setConfirmAt(LocalDateTime.now());
            t.setConfirmSrc("SELF");
            touchTask(t);
        } else if (!HrTrainTask.ST_CONFIRMED.equals(t.getStatus())) {
            throw new ServiceException(422, "当前状态不可确认（" + t.getStatus() + "），完成判定以 HR 登记为准");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", t);
        out.put("hint", "已确认参加，最终结果以 HR 登记为准");
        return out;
    }

    // ================================================================
    // internals
    // ================================================================

    /** 按对象展开任务（UK=PLAN_ID+EMP_ID 幂等） */
    private int expandTasks(HrTrainPlan p) {
        List<HrEmployee> emps = new ArrayList<>();
        if (HrTrainPlan.TARGET_DEPT.equals(p.getTargetType())) {
            emps = empDao.selectList(new LambdaQueryWrapper<HrEmployee>()
                    .eq(HrEmployee::getDeptId, p.getTargetValue())
                    .eq(HrEmployee::getDelFlag, "0")
                    .in(HrEmployee::getEmpStatus, "PROBATION", "REGULAR"));
        } else {
            for (String empId : p.getTargetValue().split(",")) {
                HrEmployee e = empDao.selectById(empId.trim());
                if (e != null) {
                    emps.add(e);
                }
            }
        }
        int expanded = 0;
        for (HrEmployee e : emps) {
            Long exists = taskDao.selectCount(new LambdaQueryWrapper<HrTrainTask>()
                    .eq(HrTrainTask::getPlanId, p.getId())
                    .eq(HrTrainTask::getEmpId, e.getId()));
            if (exists != null && exists > 0) {
                continue; // UK 幂等
            }
            HrTrainTask t = new HrTrainTask();
            t.setId(uuid());
            t.setPlanId(p.getId());
            t.setEmpId(e.getId());
            t.setEmpName(e.getName());
            t.setEmpNo(e.getEmpNo());
            t.setDeptId(e.getDeptId());
            t.setStatus(HrTrainTask.ST_PENDING);
            t.setCreateBy(currentUser());
            taskDao.insert(t);
            expanded++;
        }
        return expanded;
    }

    /** 计划行任务统计（完成率分子分母，CANCELLED 不计分母） */
    private Map<String, Object> taskStats(String planId) {
        int should = 0;
        int completed = 0;
        int failed = 0;
        int cancelled = 0;
        int pending = 0;
        for (HrTrainTask t : taskDao.selectList(new LambdaQueryWrapper<HrTrainTask>()
                .eq(HrTrainTask::getPlanId, planId)
                .eq(HrTrainTask::getDelFlag, "0"))) {
            switch (t.getStatus()) {
                case HrTrainTask.ST_COMPLETED -> completed++;
                case HrTrainTask.ST_FAILED -> failed++;
                case HrTrainTask.ST_CANCELLED -> cancelled++;
                default -> pending++;
            }
            if (!HrTrainTask.ST_CANCELLED.equals(t.getStatus())) {
                should++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("should", should);
        out.put("completed", completed);
        out.put("failed", failed);
        out.put("cancelled", cancelled);
        out.put("pending", pending);
        out.put("rate", should == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(completed * 100L).divide(BigDecimal.valueOf(should), 2,
                        java.math.RoundingMode.HALF_UP));
        return out;
    }

    // ---- 凭证 ----

    private String anchor(HrTrainPlan p) {
        return p.getPlanNo() + "@" + p.getPlanYear();
    }

    private void createVoucher(HrTrainPlan p, String anc, BigDecimal cost,
                               String expenseAcc, String payableAcc) {
        List<GlVoucherService.FinVoucherLineSpec> lines = List.of(
                GlVoucherService.FinVoucherLineSpec.of(expenseAcc, "Dr", cost,
                        "职工教育经费 " + p.getPlanNo() + " " + p.getCourseName()),
                GlVoucherService.FinVoucherLineSpec.of(payableAcc, "Cr", cost,
                        "职工教育经费 " + p.getPlanNo() + " " + p.getCourseName()));
        voucherService.create("ACC", LocalDate.now(), "培训计提-" + p.getPlanNo() + " " + p.getCourseName(),
                SRC_TRAINING, anc, null, lines);
    }

    /** 锚点下未被冲销的原始凭证 */
    private List<FinVoucher> activeVouchers(String anc) {
        List<FinVoucher> out = new ArrayList<>();
        List<FinVoucher> all = voucherDao.selectList(new LambdaQueryWrapper<FinVoucher>()
                .eq(FinVoucher::getSourceType, SRC_TRAINING)
                .eq(FinVoucher::getSourceDocNo, anc));
        for (FinVoucher v : all) {
            if (v.getReversesId() != null) {
                continue; // 冲销凭证本身
            }
            boolean reversed = voucherDao.selectCount(new LambdaQueryWrapper<FinVoucher>()
                    .eq(FinVoucher::getReversesId, v.getId())) > 0;
            if (!reversed) {
                out.add(v);
            }
        }
        return out;
    }

    private BigDecimal activeSum(List<FinVoucher> active) {
        BigDecimal sum = BigDecimal.ZERO;
        for (FinVoucher v : active) {
            if (v.getTotalDr() != null) {
                sum = sum.add(v.getTotalDr());
            }
        }
        return sum;
    }

    private int reverseActiveVouchers(String anc, String reason) {
        int n = 0;
        for (FinVoucher v : activeVouchers(anc)) {
            try {
                voucherService.reverse(v.getId(), reason, anc);
                n++;
            } catch (Exception e) {
                log.warn("train voucher reverse failed {}: {}", v.getId(), e.getMessage());
            }
        }
        return n;
    }

    private String fixedAccount(String key) {
        HrPayAccountMapping m = mappingDao.selectOne(new LambdaQueryWrapper<HrPayAccountMapping>()
                .eq(HrPayAccountMapping::getMappingType, HrPayAccountMapping.TYPE_FIXED)
                .eq(HrPayAccountMapping::getMappingKey, key)
                .eq(HrPayAccountMapping::getDelFlag, "0"));
        if (m == null) {
            throw new ServiceException(500, "培训科目映射缺失：" + key + "（迁移 165 未执行？）");
        }
        return m.getAccountCode();
    }

    // ---- 留痕 ----

    private static StringBuilder appendLog(StringBuilder json, String field, String from, String to) {
        String entry = "{\"at\":\"" + LocalDateTime.now() + "\",\"by\":\"" + currentUser()
                + "\",\"field\":\"" + field + "\",\"from\":\"" + safe(from) + "\",\"to\":\"" + safe(to) + "\"}";
        if (json.length() <= 2) {
            return new StringBuilder("[").append(entry).append("]");
        }
        return json.insert(json.length() - 1, "," + entry);
    }

    private static String appendHist(String json, BigDecimal prevScore, BigDecimal newScore,
                                     String prevStatus, String newStatus) {
        String entry = "{\"at\":\"" + LocalDateTime.now() + "\",\"by\":\"" + currentUser()
                + "\",\"prevScore\":" + (prevScore == null ? "null" : prevScore)
                + ",\"newScore\":" + newScore
                + ",\"prevStatus\":\"" + prevStatus + "\",\"newStatus\":\"" + newStatus + "\"}";
        if (json == null || json.length() <= 2) {
            return "[" + entry + "]";
        }
        return json.substring(0, json.length() - 1) + "," + entry + "]";
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\"", "'");
    }

    // ---- misc ----

    private String nextNo(int year) {
        String prefix = "TP" + year;
        int max = 0;
        for (String no : planDao.selectNosByPrefix(prefix + "%")) {
            try {
                // 只取前缀后的流水段（全 digits 会把年份算进去 → 重复号）
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignored) {
                // skip
            }
        }
        return String.format("%s%04d", prefix, max + 1);
    }

    private HrTrainPlan require(String id) {
        HrTrainPlan p = planDao.selectById(id);
        if (p == null) {
            throw new ServiceException(404, "培训计划不存在：" + id);
        }
        return p;
    }

    private HrTrainTask requireTask(String id) {
        HrTrainTask t = taskDao.selectById(id);
        if (t == null) {
            throw new ServiceException(404, "参训任务不存在：" + id);
        }
        return t;
    }

    private void touchPlan(HrTrainPlan p) {
        p.setUpdateBy(currentUser());
        p.setUpdateDate(LocalDateTime.now());
        planDao.updateById(p);
    }

    private void touchTask(HrTrainTask t) {
        t.setUpdateBy(currentUser());
        t.setUpdateDate(LocalDateTime.now());
        taskDao.updateById(t);
    }

    private static void require(String v, String label) {
        if (v == null || v.trim().isEmpty()) {
            throw new ServiceException(422, label + "必填");
        }
    }

    private static void requireScore(BigDecimal v, String label) {
        if (v == null) {
            throw new ServiceException(422, label + "必填");
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static Integer intOrNull(Object o) {
        if (o == null || String.valueOf(o).trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            throw new ServiceException(422, "整数格式非法：" + o);
        }
    }

    private static BigDecimal dec(Object o) {
        if (o == null || String.valueOf(o).trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (Exception e) {
            throw new ServiceException(422, "数值格式非法：" + o);
        }
    }

    private static LocalDate date(Object o) {
        if (o == null || String.valueOf(o).trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(o).trim());
        } catch (Exception e) {
            throw new ServiceException(422, "日期格式非法（yyyy-MM-dd）：" + o);
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String currentUser() {
        try {
            return org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication().getName();
        } catch (Exception e) {
            return "system";
        }
    }
}
