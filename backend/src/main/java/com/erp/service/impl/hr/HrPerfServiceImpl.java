package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrPerfCalibrateLogDao;
import com.erp.dao.hr.HrPerfPlanDao;
import com.erp.dao.hr.HrPerfResultDao;
import com.erp.dao.hr.HrPerfScoreDao;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrPerfCalibrateLog;
import com.erp.entity.hr.HrPerfPlan;
import com.erp.entity.hr.HrPerfResult;
import com.erp.entity.hr.HrPerfScore;
import com.erp.service.SysParamService;
import com.erp.service.hr.HrPerfService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 绩效管理实现（performance-management：方案系数锁定/评分映射/校准状态机/结果快照）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrPerfServiceImpl implements HrPerfService {

    private final HrPerfPlanDao planDao;
    private final HrPerfScoreDao scoreDao;
    private final HrPerfCalibrateLogDao logDao;
    private final HrPerfResultDao resultDao;
    private final HrEmployeeDao empDao;
    private final SysParamService sysParamService;

    // ================================================================
    // 方案
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrPerfPlan createPlan(Map<String, Object> payload) {
        String name = str(payload.get("name"));
        require(name, "方案名称");
        String periodType = str(payload.get("periodType"));
        if (periodType == null || periodType.isBlank()) {
            periodType = "HALF";
        }
        String period = str(payload.get("period"));
        require(period, "周期");
        String deptId = str(payload.get("deptId"));
        String ukPerm = (deptId == null || deptId.isBlank() ? "ALL" : deptId) + "#" + period;
        // 同周期同部门唯一生效（VOID 不占位）
        Long exists = planDao.selectCount(new LambdaQueryWrapper<HrPerfPlan>()
                .eq(HrPerfPlan::getUkPerm, ukPerm)
                .eq(HrPerfPlan::getDelFlag, "0")
                .ne(HrPerfPlan::getStatus, HrPerfPlan.ST_VOID));
        if (exists != null && exists > 0) {
            throw new ServiceException(422, "该周期该部门已有生效方案，请作废后重建");
        }
        String gradeJson = str(payload.get("gradeJson"));
        if (gradeJson == null || gradeJson.isBlank()) {
            gradeJson = defaultGradeJson();
        }
        HrPerfPlan p = new HrPerfPlan();
        p.setId(uuid());
        p.setPlanNo(nextPlanNo());
        p.setName(name);
        p.setDeptId(deptId);
        p.setPeriodType(periodType);
        p.setPeriod(period);
        p.setGradeJson(gradeJson);
        p.setStatus(HrPerfPlan.ST_ACTIVE);
        p.setUkPerm(ukPerm);
        p.setCreateBy(currentUser());
        p.setCreateDate(LocalDateTime.now());
        planDao.insert(p);
        log.info("perf plan created: {} {}", p.getPlanNo(), period);
        return p;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrPerfPlan voidPlan(String planId) {
        HrPerfPlan p = requirePlan(planId);
        if (HrPerfPlan.ST_CLOSED.equals(p.getStatus()) || HrPerfPlan.ST_VOID.equals(p.getStatus())) {
            throw new ServiceException(422, "已关闭/已作废方案不可再作废");
        }
        Long scored = scoreDao.selectCount(new LambdaQueryWrapper<HrPerfScore>()
                .eq(HrPerfScore::getPlanId, planId)
                .eq(HrPerfScore::getDelFlag, "0"));
        if (scored != null && scored > 0) {
            throw new ServiceException(422, "方案已有评分记录，不可作废");
        }
        p.setStatus(HrPerfPlan.ST_VOID);
        // MySQL 无部分唯一索引：作废时改写 UK 槽位释放同周期唯一约束（否则作废重建撞 UK）
        p.setUkPerm(p.getUkPerm() + "#VOID-" + System.currentTimeMillis());
        p.setUpdateBy(currentUser());
        p.setUpdateDate(LocalDateTime.now());
        planDao.updateById(p);
        return p;
    }

    @Override
    public List<HrPerfPlan> planList(String period, String deptId, String status) {
        LambdaQueryWrapper<HrPerfPlan> qw = new LambdaQueryWrapper<HrPerfPlan>()
                .eq(HrPerfPlan::getDelFlag, "0")
                .orderByDesc(HrPerfPlan::getPeriod);
        if (notBlank(period)) {
            qw.eq(HrPerfPlan::getPeriod, period);
        }
        if (notBlank(deptId)) {
            qw.eq(HrPerfPlan::getDeptId, deptId);
        }
        if (notBlank(status)) {
            qw.eq(HrPerfPlan::getStatus, status);
        }
        return planDao.selectList(qw);
    }

    // ================================================================
    // 评分
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrPerfScore enterScore(Map<String, Object> payload) {
        String planId = str(payload.get("planId"));
        HrPerfPlan plan = requirePlan(planId);
        if (HrPerfPlan.ST_CLOSED.equals(plan.getStatus()) || HrPerfPlan.ST_VOID.equals(plan.getStatus())) {
            throw new ServiceException(422, "周期已关闭/方案已作废，不可录入评分");
        }
        String empId = str(payload.get("empId"));
        HrEmployee e = empId == null ? null : empDao.selectById(empId);
        if (e == null) {
            throw new ServiceException(404, "员工不存在：" + empId);
        }
        BigDecimal score = dec(payload.get("score"));
        String grade = str(payload.get("grade"));
        if (grade == null || grade.isBlank()) {
            if (score == null) {
                throw new ServiceException(422, "分值或等级至少一项");
            }
            grade = mapGrade(plan.getGradeJson(), score);
        }
        BigDecimal coef = gradeCoef(plan.getGradeJson(), grade);
        HrPerfScore s = new HrPerfScore();
        s.setId(uuid());
        s.setPlanId(planId);
        s.setPeriod(plan.getPeriod());
        s.setEmpId(e.getId());
        s.setEmpNo(e.getEmpNo());
        s.setEmpName(e.getName());
        s.setDeptId(e.getDeptId());
        s.setScore(score);
        s.setGrade(grade);
        s.setCoef(coef);
        s.setCommentText(str(payload.get("commentText")));
        s.setStatus(HrPerfScore.ST_DRAFT);
        s.setCreateBy(currentUser());
        s.setCreateDate(LocalDateTime.now());
        scoreDao.insert(s);
        writeLog(s, HrPerfCalibrateLog.ACT_SUBMIT, null, grade, "录入");
        return s;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrPerfScore submitScore(String scoreId) {
        HrPerfScore s = requireScore(scoreId);
        if (!HrPerfScore.ST_DRAFT.equals(s.getStatus())) {
            throw new ServiceException(422, "仅 DRAFT 可提交（当前：" + s.getStatus() + "）");
        }
        s.setStatus(HrPerfScore.ST_SUBMITTED);
        s.setSubmitAt(LocalDateTime.now());
        s.setUpdateBy(currentUser());
        s.setUpdateDate(LocalDateTime.now());
        scoreDao.updateById(s);
        writeLog(s, HrPerfCalibrateLog.ACT_SUBMIT, s.getGrade(), s.getGrade(), "提交校准");
        return s;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> batchSubmit(List<String> scoreIds) {
        int ok = 0;
        List<String> errors = new ArrayList<>();
        for (String id : scoreIds == null ? List.<String>of() : scoreIds) {
            try {
                submitScore(id);
                ok++;
            } catch (ServiceException ex) {
                errors.add(id + ": " + ex.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("submitted", ok);
        out.put("errors", errors);
        return out;
    }

    @Override
    public List<HrPerfScore> scoreList(String period, String planId, String empId, String status) {
        LambdaQueryWrapper<HrPerfScore> qw = new LambdaQueryWrapper<HrPerfScore>()
                .eq(HrPerfScore::getDelFlag, "0")
                .orderByAsc(HrPerfScore::getGrade);
        if (notBlank(period)) {
            qw.eq(HrPerfScore::getPeriod, period);
        }
        if (notBlank(planId)) {
            qw.eq(HrPerfScore::getPlanId, planId);
        }
        if (notBlank(empId)) {
            qw.eq(HrPerfScore::getEmpId, empId);
        }
        if (notBlank(status)) {
            qw.eq(HrPerfScore::getStatus, status);
        }
        return scoreDao.selectList(qw);
    }

    // ================================================================
    // 校准状态机（自建，批量端点+日志留痕，不接 ApprovalEngine）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> calibrate(Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) payload.get("scoreIds");
        String action = str(payload.get("action"));
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException(422, "scoreIds 必填");
        }
        String toGrade = str(payload.get("toGrade"));
        String reason = str(payload.get("reason"));
        int ok = 0;
        List<String> errors = new ArrayList<>();
        for (String id : ids) {
            try {
                HrPerfScore s = requireScore(id);
                if ("CALIBRATE".equals(action)) {
                    // SUBMITTED → CALIBRATING → CALIBRATED（一轮批量内完成流转）
                    if (!HrPerfScore.ST_SUBMITTED.equals(s.getStatus())
                            && !HrPerfScore.ST_CALIBRATING.equals(s.getStatus())) {
                        throw new ServiceException(422, "仅 SUBMITTED/CALIBRATING 可校准");
                    }
                    if (toGrade != null && !toGrade.isBlank() && !toGrade.equals(s.getGrade())) {
                        require(reason, "等级调整原因");
                        String from = s.getGrade();
                        s.setGrade(toGrade);
                        s.setCoef(gradeCoef(requirePlan(s.getPlanId()).getGradeJson(), toGrade));
                        writeLog(s, HrPerfCalibrateLog.ACT_ADJUST, from, toGrade, reason);
                    } else {
                        writeLog(s, HrPerfCalibrateLog.ACT_CALIBRATE, s.getGrade(), s.getGrade(), reason);
                    }
                    s.setStatus(HrPerfScore.ST_CALIBRATED);
                } else if ("REJECT".equals(action)) {
                    require(reason, "驳回原因");
                    if (HrPerfScore.ST_CONFIRMED.equals(s.getStatus())) {
                        throw new ServiceException(422, "已确认评分不可驳回");
                    }
                    s.setStatus(HrPerfScore.ST_DRAFT);
                    writeLog(s, HrPerfCalibrateLog.ACT_REJECT, s.getGrade(), s.getGrade(), reason);
                } else {
                    throw new ServiceException(422, "action 仅支持 CALIBRATE/REJECT");
                }
                s.setUpdateBy(currentUser());
                s.setUpdateDate(LocalDateTime.now());
                scoreDao.updateById(s);
                ok++;
            } catch (ServiceException ex) {
                errors.add(id + ": " + ex.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("processed", ok);
        out.put("errors", errors);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirm(Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) payload.get("scoreIds");
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException(422, "scoreIds 必填");
        }
        int ok = 0;
        List<String> errors = new ArrayList<>();
        for (String id : ids) {
            try {
                HrPerfScore s = requireScore(id);
                if (!HrPerfScore.ST_CALIBRATED.equals(s.getStatus())) {
                    throw new ServiceException(422, "仅 CALIBRATED 可确认");
                }
                s.setStatus(HrPerfScore.ST_CONFIRMED);
                s.setConfirmAt(LocalDateTime.now());
                s.setConfirmBy(currentUser());
                s.setUpdateBy(currentUser());
                s.setUpdateDate(LocalDateTime.now());
                scoreDao.updateById(s);
                writeLog(s, HrPerfCalibrateLog.ACT_CONFIRM, s.getGrade(), s.getGrade(), "确认结果");
                // 结果快照（CONFIRMED 后不可改，薪酬取数来源）
                HrPerfResult r = new HrPerfResult();
                r.setId(uuid());
                r.setPeriod(s.getPeriod());
                r.setEmpId(s.getEmpId());
                r.setEmpNo(s.getEmpNo());
                r.setEmpName(s.getEmpName());
                r.setDeptId(s.getDeptId());
                r.setGrade(s.getGrade());
                r.setCoef(s.getCoef());
                r.setScoreId(s.getId());
                r.setConfirmBy(currentUser());
                r.setConfirmAt(LocalDateTime.now());
                r.setCreateBy(currentUser());
                r.setCreateDate(LocalDateTime.now());
                resultDao.insert(r);
                ok++;
            } catch (ServiceException ex) {
                errors.add(id + ": " + ex.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("confirmed", ok);
        out.put("errors", errors);
        return out;
    }

    @Override
    public List<HrPerfResult> resultList(String period, String deptId) {
        LambdaQueryWrapper<HrPerfResult> qw = new LambdaQueryWrapper<HrPerfResult>()
                .eq(HrPerfResult::getDelFlag, "0")
                .orderByAsc(HrPerfResult::getGrade);
        if (notBlank(period)) {
            qw.eq(HrPerfResult::getPeriod, period);
        }
        if (notBlank(deptId)) {
            qw.eq(HrPerfResult::getDeptId, deptId);
        }
        return resultDao.selectList(qw);
    }

    @Override
    public List<Map<String, Object>> calibrateLogs(String scoreId) {
        List<HrPerfCalibrateLog> logs = logDao.selectList(new LambdaQueryWrapper<HrPerfCalibrateLog>()
                .eq(HrPerfCalibrateLog::getScoreId, scoreId)
                .eq(HrPerfCalibrateLog::getDelFlag, "0")
                .orderByAsc(HrPerfCalibrateLog::getOpAt));
        List<Map<String, Object>> out = new ArrayList<>();
        for (HrPerfCalibrateLog l : logs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", l.getAction());
            m.put("fromGrade", l.getFromGrade());
            m.put("toGrade", l.getToGrade());
            m.put("reason", l.getReason());
            m.put("operator", l.getOperator());
            m.put("opAt", l.getOpAt());
            out.add(m);
        }
        return out;
    }

    // ================================================================
    // helpers
    // ================================================================

    private void writeLog(HrPerfScore s, String action, String from, String to, String reason) {
        HrPerfCalibrateLog l = new HrPerfCalibrateLog();
        l.setId(uuid());
        l.setScoreId(s.getId());
        l.setPeriod(s.getPeriod());
        l.setAction(action);
        l.setFromGrade(from);
        l.setToGrade(to);
        l.setReason(reason);
        l.setOperator(currentUser());
        l.setOpAt(LocalDateTime.now());
        l.setCreateBy(currentUser());
        l.setCreateDate(LocalDateTime.now());
        logDao.insert(l);
    }

    /** 方案等级 JSON → 分值映射等级（按区间）。 */
    @SuppressWarnings("unchecked")
    private String mapGrade(String gradeJson, BigDecimal score) {
        List<Map<String, Object>> grades = parseGrades(gradeJson);
        for (Map<String, Object> g : grades) {
            BigDecimal min = dec(g.get("minScore"));
            BigDecimal max = dec(g.get("maxScore"));
            if (min != null && max != null
                    && score.compareTo(min) >= 0 && score.compareTo(max) <= 0) {
                return String.valueOf(g.get("grade"));
            }
        }
        throw new ServiceException(422, "分值 " + score + " 不在任何等级区间内");
    }

    @SuppressWarnings("unchecked")
    private BigDecimal gradeCoef(String gradeJson, String grade) {
        for (Map<String, Object> g : parseGrades(gradeJson)) {
            if (grade.equals(String.valueOf(g.get("grade")))) {
                BigDecimal coef = dec(g.get("coef"));
                if (coef != null) {
                    return coef;
                }
            }
        }
        throw new ServiceException(422, "等级 " + grade + " 不在方案等级定义中");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseGrades(String gradeJson) {
        try {
            List<Map<String, Object>> list = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(gradeJson, List.class);
            if (list == null || list.isEmpty()) {
                throw new IllegalStateException("empty");
            }
            return list;
        } catch (Exception e) {
            String tail = gradeJson == null ? "null"
                    : gradeJson.substring(0, Math.min(80, gradeJson.length()));
            throw new ServiceException(422, "等级定义 JSON 非法（" + tail + "…）");
        }
    }

    /**
     * 方案未指定等级时取 SysParam 默认 PERF_GRADE_JSON（紧凑短键 [{g,c,min}]，
     * 上界 = 下一档 min、末档 100；varchar(128) 列宽所限用短键），转方案长键格式。
     * 参数缺失（进程缓存早于迁移插入等）用内置默认兜底。
     */
    private String defaultGradeJson() {
        String compact = sysParamService.getValue("PERF_GRADE_JSON");
        if (compact == null || compact.isBlank()) {
            compact = "[{\"g\":\"A\",\"c\":1.2,\"min\":90},{\"g\":\"B\",\"c\":1.0,\"min\":80},"
                    + "{\"g\":\"C\",\"c\":0.8,\"min\":70},{\"g\":\"D\",\"c\":0.6,\"min\":0}]";
        }
        List<Map<String, Object>> rows;
        try {
            rows = new com.fasterxml.jackson.databind.ObjectMapper().readValue(compact, List.class);
        } catch (Exception e) {
            throw new ServiceException(422, "PERF_GRADE_JSON 配置非法");
        }
        List<Map<String, Object>> grades = new java.util.ArrayList<>();
        // rows 按 min 降序（A90/B80/C70/D0）：首档上界 100，第 i 档上界 = 第 i-1 档 min - 0.01
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("grade", String.valueOf(r.get("g") != null ? r.get("g") : r.get("grade")));
            g.put("coef", r.get("c") != null ? r.get("c") : r.get("coef"));
            BigDecimal min = dec(r.get("min") != null ? r.get("min") : r.get("minScore"));
            BigDecimal max = dec(r.get("maxScore"));
            g.put("minScore", min);
            if (max == null) {
                max = (i == 0)
                        ? new BigDecimal("100")
                        : dec(rows.get(i - 1).get("min") != null ? rows.get(i - 1).get("min") : rows.get(i - 1).get("minScore"))
                                .subtract(new BigDecimal("0.01"));
            }
            g.put("maxScore", max);
            grades.add(g);
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(grades);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException(422, "等级定义序列化失败");
        }
    }

    private String nextPlanNo() {
        String prefix = "PL" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        List<String> existing = planDao.selectNosByPrefix(prefix + "%");
        int max = 0;
        for (String no : existing) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(no.lastIndexOf('-') + 1)));
            } catch (Exception ignored) {
                // skip
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private HrPerfPlan requirePlan(String id) {
        HrPerfPlan p = planDao.selectById(id);
        if (p == null) {
            throw new ServiceException(404, "绩效方案不存在：" + id);
        }
        return p;
    }

    private HrPerfScore requireScore(String id) {
        HrPerfScore s = scoreDao.selectById(id);
        if (s == null) {
            throw new ServiceException(404, "绩效评分不存在：" + id);
        }
        return s;
    }

    private static void require(String v, String label) {
        if (v == null || v.trim().isEmpty()) {
            throw new ServiceException(422, label + "必填");
        }
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
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
