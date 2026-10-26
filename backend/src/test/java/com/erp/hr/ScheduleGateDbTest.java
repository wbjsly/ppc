package com.erp.hr;

import com.erp.common.ServiceException;
import com.erp.service.hr.HrShiftService;
import com.erp.service.hr.HrTrainSickScheduleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排班门禁 DbTest（attendance-core 新需求，BR-4.7-06）：单条拦截/批量复制反馈/
 * 非关键与无证书放行/带病清单处置（取消/改派）。
 * 造数前缀 tg- / tge- / 班次 tshift-g。
 */
@SpringBootTest
class ScheduleGateDbTest {

    private static final String SHIFT_ID = "tshift-g";

    @Autowired
    private HrShiftService shiftService;
    @Autowired
    private HrTrainSickScheduleService sickService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        jdbc.update("INSERT INTO hr_shift (ID, SHIFT_NO, NAME, START_TIME, END_TIME, HOURS, CROSS_DAY, "
                + "OVERTIME_ELIGIBLE, TOLERANCE_MIN, STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, 'TG01', '门禁测试班', '09:00', '18:00', 8, '0', '1', 10, '1', 'junit', '0', 0)",
                SHIFT_ID);
        seedEmp("gate1", "TG-EMP-1");   // 关键类 EXPIRED → 拦
        seedEmp("gate2", "TG-EMP-2");   // 关键类 EXPIRED → 拦（改派目标）
        seedEmp("ok", "TG-EMP-OK");     // 无证书 → 放行
        seedEmp("plain", "TG-EMP-P");   // 非关键 EXPIRED → 放行
        seedExpiredCert("gate1", "CERTC-DEMO-ELEC");   // 迁移 DEMO 关键类
        seedExpiredCert("gate2", "CERTC-DEMO-WELD");
        seedExpiredCert("plain", "CERTC-DEMO-HEALTH"); // DEMO 非关键类
        jdbc.update("INSERT INTO hr_dept (ID, DEPT_NO, NAME, STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES ('tdept-gate', 'TGDEPT', '门禁测试部', '1', 'junit', '0', 0)");
        jdbc.update("UPDATE hr_employee SET DEPT_ID='tdept-gate' WHERE ID LIKE 'tge-emp-%'");
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM hr_schedule WHERE EMP_ID LIKE 'tge-emp-%'");
        jdbc.update("DELETE FROM hr_employee_cert WHERE EMP_ID LIKE 'tge-emp-%'");
        jdbc.update("DELETE FROM hr_employee WHERE ID LIKE 'tge-emp-%' OR EMP_NO LIKE 'TG-EMP-%'");
        jdbc.update("DELETE FROM hr_dept WHERE ID='tdept-gate'");
        jdbc.update("DELETE FROM hr_shift WHERE ID=?", SHIFT_ID);
    }

    private void seedEmp(String suffix, String empNo) {
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, EMP_STATUS, HIRE_DATE, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, ?, ?, 'REGULAR', ?, 'junit', '0', 0)",
                "tge-emp-" + suffix, empNo, "门禁员工" + suffix, "TGEID-" + suffix, LocalDate.now().minusYears(1));
    }

    /** 直插已失效证书（绕过审核直达 EXPIRED，模拟存量） */
    private void seedExpiredCert(String suffix, String cateId) {
        String cateName = jdbc.query("SELECT CATE_NAME FROM hr_cert_category WHERE ID=?",
                rs -> rs.next() ? rs.getString(1) : null, cateId);
        jdbc.update("INSERT INTO hr_employee_cert (ID, EMP_ID, EMP_NAME, CATE_ID, CATE_NAME, VALID_TO, STATUS, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, ?, ?, ?, ?, 'EXPIRED', 'junit', '0', 0)",
                "tge-cert-" + suffix, "tge-emp-" + suffix, "门禁员工" + suffix, cateId, cateName,
                LocalDate.now().minusDays(5));
    }

    private Map<String, Object> singlePayload(String empId, LocalDate workDate) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("empId", empId);
        p.put("workDate", workDate.toString());
        p.put("shiftId", SHIFT_ID);
        return p;
    }

    @Test
    void singleBlockedAndReleases() {
        LocalDate d = LocalDate.now().plusDays(7);
        // 失效员工：L1 拒绝且文案含证书信息
        ServiceException ex = assertThrows(ServiceException.class,
                () -> shiftService.schedule(singlePayload("tge-emp-gate1", d)));
        assertTrue(ex.getMessage().contains("认证失效限制排班上岗"), ex.getMessage());
        assertTrue(ex.getMessage().contains("特种作业证"), ex.getMessage());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM hr_schedule WHERE EMP_ID='tge-emp-gate1'",
                Integer.class));
        // 无证书员工放行
        shiftService.schedule(singlePayload("tge-emp-ok", d));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM hr_schedule WHERE EMP_ID='tge-emp-ok'",
                Integer.class));
        // 非关键失效放行
        shiftService.schedule(singlePayload("tge-emp-plain", d));
        // 恢复（旧证归档，移除 EXPIRED 关键记录）后放行
        jdbc.update("UPDATE hr_employee_cert SET STATUS='ARCHIVED' WHERE EMP_ID='tge-emp-gate1'");
        shiftService.schedule(singlePayload("tge-emp-gate1", d));
    }

    @Test
    void bulkMixedFeedback() {
        LocalDate d = LocalDate.now().plusDays(8);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deptId", "tdept-gate");
        payload.put("fromDate", d.toString());
        payload.put("toDate", d.toString());
        payload.put("shiftId", SHIFT_ID);
        Map<String, Object> out = shiftService.bulkSchedule(payload);
        // 部门 4 人：2 失效被拦、2 成功（ok + plain）
        assertEquals(2, ((Number) out.get("rows")).intValue(), "成功人数：" + out);
        assertEquals(2, ((Number) out.get("failedCount")).intValue(), "失败人数：" + out);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> failed = (java.util.List<Map<String, Object>>) out.get("failed");
        assertTrue(failed.get(0).get("reason").toString().contains("认证失效"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM hr_schedule WHERE WORK_DATE=?",
                Integer.class, d));
    }

    @Test
    void copyMonthFeedbackAndRestNotBlocked() {
        LocalDate today = LocalDate.now();
        // 源月：失效员工有排班（存量），休息日行不拦
        jdbc.update("INSERT INTO hr_schedule (ID, EMP_ID, WORK_DATE, SHIFT_ID, IS_REST, SOURCE, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES ('tge-sch-src1', 'tge-emp-gate1', ?, ?, '0', 'SINGLE', 'junit', '0', 0)",
                today.withDayOfMonth(1), SHIFT_ID);
        jdbc.update("INSERT INTO hr_schedule (ID, EMP_ID, WORK_DATE, IS_REST, SOURCE, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES ('tge-sch-src2', 'tge-emp-gate1', ?, '1', 'SINGLE', 'junit', '0', 0)",
                today.withDayOfMonth(2));
        jdbc.update("INSERT INTO hr_schedule (ID, EMP_ID, WORK_DATE, SHIFT_ID, IS_REST, SOURCE, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES ('tge-sch-src3', 'tge-emp-ok', ?, ?, '0', 'SINGLE', 'junit', '0', 0)",
                today.withDayOfMonth(1), SHIFT_ID);
        String from = String.format("%04d%02d", today.getYear(), today.getMonthValue());
        String to = String.format("%04d%02d", today.plusMonths(1).getYear(), today.plusMonths(1).getMonthValue());
        Map<String, Object> out = shiftService.copyMonth(from, to);
        // 3 行源：gate1 上岗被拦 1、gate1 休息日放行 1、ok 放行 1
        assertEquals(2, ((Number) out.get("copied")).intValue(), "复制成功：" + out);
        assertEquals(1, ((Number) out.get("failedCount")).intValue(), "门禁失败：" + out);
    }

    @Test
    void sickListAndDispose() {
        LocalDate d1 = LocalDate.now().plusDays(5);
        LocalDate d2 = LocalDate.now().plusDays(6);
        // 存量带病排班（直插绕过门禁）
        jdbc.update("INSERT INTO hr_schedule (ID, EMP_ID, WORK_DATE, SHIFT_ID, IS_REST, SOURCE, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES ('tge-sch-sick1', 'tge-emp-gate1', ?, ?, '0', 'SINGLE', 'junit', '0', 0)", d1, SHIFT_ID);
        jdbc.update("INSERT INTO hr_schedule (ID, EMP_ID, WORK_DATE, SHIFT_ID, IS_REST, SOURCE, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES ('tge-sch-sick2', 'tge-emp-gate1', ?, ?, '0', 'SINGLE', 'junit', '0', 0)", d2, SHIFT_ID);
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "junit-hr", null,
                org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("ROLE_HR"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        // 清单
        assertEquals(2, sickService.list().size());
        // 取消处置
        sickService.dispose("tge-sch-sick1", "CANCEL", null);
        assertEquals(1, sickService.list().size());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM hr_schedule WHERE ID='tge-sch-sick1' "
                + "AND DEL_FLAG='1'", Integer.class));
        // 改派至有效员工 → 成功；改派至同样失效员工 → 422
        Map<String, Object> reassigned = sickService.dispose("tge-sch-sick2", "REASSIGN", "tge-emp-ok");
        assertTrue(String.valueOf(reassigned.get("result")).contains("门禁员工ok"));
        assertEquals(0, sickService.list().size());
        jdbc.update("INSERT INTO hr_schedule (ID, EMP_ID, WORK_DATE, SHIFT_ID, IS_REST, SOURCE, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES ('tge-sch-sick3', 'tge-emp-gate2', ?, ?, '0', 'SINGLE', 'junit', '0', 0)", d2, SHIFT_ID);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> sickService.dispose("tge-sch-sick3", "REASSIGN", "tge-emp-gate1"));
        assertTrue(ex.getMessage().contains("同样认证失效"), ex.getMessage());
    }
}
