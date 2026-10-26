package com.erp.hr;

import com.erp.common.ServiceException;
import com.erp.dao.hr.HrPerfCalibrateLogDao;
import com.erp.dao.hr.HrPerfPlanDao;
import com.erp.dao.hr.HrPerfResultDao;
import com.erp.dao.hr.HrPerfScoreDao;
import com.erp.entity.hr.HrPerfCalibrateLog;
import com.erp.entity.hr.HrPerfPlan;
import com.erp.entity.hr.HrPerfResult;
import com.erp.entity.hr.HrPerfScore;
import com.erp.service.hr.HrPerfService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 绩效管理 DbTest（performance-management：方案唯一/系数锁定/映射/校准留痕/结果快照）。 */
@SpringBootTest
class PerformanceDbTest {

    @Autowired
    private HrPerfService perfService;
    @Autowired
    private HrPerfPlanDao planDao;
    @Autowired
    private HrPerfScoreDao scoreDao;
    @Autowired
    private HrPerfCalibrateLogDao logDao;
    @Autowired
    private HrPerfResultDao resultDao;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        loginRole("ROLE_HR");
    }

    private void purge() {
        jdbc.update("DELETE FROM hr_perf_calibrate_log WHERE SCORE_ID LIKE 'junit-perf-%'");
        jdbc.update("DELETE FROM hr_perf_result WHERE EMP_ID LIKE 'junit-perf-%'");
        jdbc.update("DELETE FROM hr_perf_score WHERE EMP_ID LIKE 'junit-perf-%' OR PLAN_ID LIKE 'junit-perf-plan-%'");
        jdbc.update("DELETE FROM hr_perf_plan WHERE PLAN_NO LIKE 'PL%' AND CREATE_BY LIKE 'junit%'");
        jdbc.update("DELETE FROM hr_employee WHERE ID LIKE 'junit-perf-%' OR EMP_NO LIKE 'JPF-%'");
    }

    private void loginRole(String role) {
        var auth = new UsernamePasswordAuthenticationToken("junit-" + role.toLowerCase(), null,
                AuthorityUtils.createAuthorityList(role));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private String seedEmp(String suffix) {
        String id = "junit-perf-" + suffix;
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, EMP_STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, ?, ?, 'REGULAR', 'junit', '0', 0)",
                id, "JPF-" + suffix.toUpperCase(), "绩效员工" + suffix, "JPFID-" + suffix);
        return id;
    }

    private Map<String, Object> planPayload(String period) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", "测试方案-" + period);
        p.put("periodType", "HALF");
        p.put("period", period);
        return p;
    }

    // ================================================================
    // 方案
    // ================================================================

    @Test
    void planUniquePerPeriodDept() {
        HrPerfPlan p1 = perfService.createPlan(planPayload("2099H1"));
        assertEquals(HrPerfPlan.ST_ACTIVE, p1.getStatus());
        assertNotNull(p1.getPlanNo());
        assertTrue(p1.getGradeJson().contains("\"A\""), "默认等级 JSON 含 A");
        // 同周期再建 → 422
        ServiceException e = assertThrows(ServiceException.class,
                () -> perfService.createPlan(planPayload("2099H1")));
        assertTrue(e.getMessage().contains("已有生效方案"), e.getMessage());
        // 作废后可重建
        perfService.voidPlan(p1.getId());
        HrPerfPlan p2 = perfService.createPlan(planPayload("2099H1"));
        assertEquals(HrPerfPlan.ST_ACTIVE, p2.getStatus());
    }

    @Test
    void voidPlanBlockedWhenScored() {
        HrPerfPlan p = perfService.createPlan(planPayload("2099H2"));
        String empId = seedEmp("vp");
        Map<String, Object> sp = new LinkedHashMap<>();
        sp.put("planId", p.getId());
        sp.put("empId", empId);
        sp.put("score", 85);
        perfService.enterScore(sp);
        ServiceException e = assertThrows(ServiceException.class, () -> perfService.voidPlan(p.getId()));
        assertTrue(e.getMessage().contains("已有评分"), e.getMessage());
    }

    // ================================================================
    // 评分映射与提交
    // ================================================================

    @Test
    void scoreAutoMapsGradeAndSubmit() {
        HrPerfPlan p = perfService.createPlan(planPayload("2099Q3"));
        String empId = seedEmp("map");
        Map<String, Object> sp = new LinkedHashMap<>();
        sp.put("planId", p.getId());
        sp.put("empId", empId);
        sp.put("score", 92);
        HrPerfScore s = perfService.enterScore(sp);
        assertEquals("A", s.getGrade(), "92 分映射 A");
        assertEquals(0, new BigDecimal("1.2").compareTo(s.getCoef()), "A 系数 1.2");
        assertEquals(HrPerfScore.ST_DRAFT, s.getStatus());
        HrPerfScore submitted = perfService.submitScore(s.getId());
        assertEquals(HrPerfScore.ST_SUBMITTED, submitted.getStatus());
        // 非区间分值 → 422
        Map<String, Object> bad = new LinkedHashMap<>(sp);
        bad.put("empId", seedEmp("map2"));
        bad.put("score", 150);
        ServiceException e = assertThrows(ServiceException.class, () -> perfService.enterScore(bad));
        assertTrue(e.getMessage().contains("区间"), e.getMessage());
    }

    // ================================================================
    // 校准状态机（批量+等级调整留痕+驳回）
    // ================================================================

    @Test
    void calibrateStateMachineWithAudit() {
        HrPerfPlan p = perfService.createPlan(planPayload("2099Q4"));
        String e1 = seedEmp("c1");
        String e2 = seedEmp("c2");
        HrPerfScore s1 = enterAndSubmit(p.getId(), e1, 95); // A
        HrPerfScore s2 = enterAndSubmit(p.getId(), e2, 85); // B
        // 批量校准：s1 由 A 调整为 B（部门拉平，须原因）
        Map<String, Object> cal = new LinkedHashMap<>();
        cal.put("scoreIds", List.of(s1.getId(), s2.getId()));
        cal.put("action", "CALIBRATE");
        cal.put("toGrade", "B");
        cal.put("reason", "部门拉平");
        Map<String, Object> out = perfService.calibrate(cal);
        assertEquals(2, ((Number) out.get("processed")).intValue());
        HrPerfScore r1 = scoreDao.selectById(s1.getId());
        assertEquals("B", r1.getGrade(), "等级调整为 B");
        assertEquals(0, new BigDecimal("1.0").compareTo(r1.getCoef()));
        assertEquals(HrPerfScore.ST_CALIBRATED, r1.getStatus());
        // 校准日志含调整前后
        List<Map<String, Object>> logs = perfService.calibrateLogs(s1.getId());
        assertTrue(logs.stream().anyMatch(l -> "ADJUST".equals(l.get("action"))
                && "A".equals(l.get("fromGrade")) && "B".equals(l.get("toGrade"))), "调整留痕");
        // 调整不带原因 → 该条进 errors（批量端点收集不外抛），状态不变
        HrPerfScore s3 = enterAndSubmit(p.getId(), seedEmp("c3"), 75);
        Map<String, Object> cal2 = new LinkedHashMap<>();
        cal2.put("scoreIds", List.of(s3.getId()));
        cal2.put("action", "CALIBRATE");
        cal2.put("toGrade", "A");
        Map<String, Object> out2 = perfService.calibrate(cal2);
        assertEquals(0, ((Number) out2.get("processed")).intValue());
        assertTrue(((List<?>) out2.get("errors")).size() == 1, "缺原因进 errors");
        assertEquals(HrPerfScore.ST_SUBMITTED, scoreDao.selectById(s3.getId()).getStatus(), "状态不变");
        // 驳回 → 回退 DRAFT
        Map<String, Object> rej = new LinkedHashMap<>();
        rej.put("scoreIds", List.of(s3.getId()));
        rej.put("action", "REJECT");
        rej.put("reason", "依据不足");
        perfService.calibrate(rej);
        assertEquals(HrPerfScore.ST_DRAFT, scoreDao.selectById(s3.getId()).getStatus());
    }

    // ================================================================
    // 确认与结果快照（薪酬取数）
    // ================================================================

    @Test
    void confirmGeneratesResultSnapshot() {
        HrPerfPlan p = perfService.createPlan(planPayload("2099Q1"));
        String empId = seedEmp("res");
        HrPerfScore s = enterAndSubmit(p.getId(), empId, 88); // B
        Map<String, Object> cal = new LinkedHashMap<>();
        cal.put("scoreIds", List.of(s.getId()));
        cal.put("action", "CALIBRATE");
        perfService.calibrate(cal);
        Map<String, Object> conf = new LinkedHashMap<>();
        conf.put("scoreIds", List.of(s.getId()));
        Map<String, Object> out = perfService.confirm(conf);
        assertEquals(1, ((Number) out.get("confirmed")).intValue());
        assertEquals(HrPerfScore.ST_CONFIRMED, scoreDao.selectById(s.getId()).getStatus());
        List<HrPerfResult> results = perfService.resultList("2099Q1", null);
        assertEquals(1, results.size());
        assertEquals("B", results.get(0).getGrade());
        assertEquals(0, new BigDecimal("1.0").compareTo(results.get(0).getCoef()));
        assertNotNull(results.get(0).getConfirmBy());
        // 非 CALIBRATED 确认 → error
        Map<String, Object> conf2 = new LinkedHashMap<>();
        conf2.put("scoreIds", List.of(s.getId()));
        Map<String, Object> out2 = perfService.confirm(conf2);
        assertTrue(((List<?>) out2.get("errors")).size() == 1, "重复确认进 errors");
    }

    // ================================================================
    // helpers
    // ================================================================

    private HrPerfScore enterAndSubmit(String planId, String empId, int score) {
        Map<String, Object> sp = new LinkedHashMap<>();
        sp.put("planId", planId);
        sp.put("empId", empId);
        sp.put("score", score);
        HrPerfScore s = perfService.enterScore(sp);
        return perfService.submitScore(s.getId());
    }
}
