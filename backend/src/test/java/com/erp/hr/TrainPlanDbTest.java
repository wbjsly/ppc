package com.erp.hr;

import com.erp.common.ServiceException;
import com.erp.service.hr.HrTrainPlanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 培训计划 DbTest（training-plan-execution）：编制即发布/展开幂等/变更留痕/
 * 作废连带/自助确认/结果登记与矩阵联动/完成率/催办。
 * 造数前缀 tpc- / tplan-。
 */
@SpringBootTest
class TrainPlanDbTest {

    @Autowired
    private HrTrainPlanService planService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        jdbc.update("INSERT INTO hr_dept (ID, DEPT_NO, NAME, STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES ('tdept-train', 'TRDEPT', '培训测试部', '1', 'junit', '0', 0)");
        seedEmp("a");
        seedEmp("b");
        seedEmp("solo");
        jdbc.update("UPDATE hr_employee SET DEPT_ID='tdept-train' WHERE ID IN ('tpc-emp-a','tpc-emp-b')");
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    private void purge() {
        jdbc.update("DELETE n FROM erp_sys_notice n JOIN hr_train_task t ON n.BIZ_ID=t.ID "
                + "JOIN hr_train_plan p ON t.PLAN_ID=p.ID WHERE p.PLAN_NO LIKE 'TP2098%' OR p.PLAN_NO LIKE 'TP2099%'");
        jdbc.update("DELETE FROM hr_remind WHERE REMIND_TYPE='TRAIN_TASK' AND EMP_ID LIKE 'tpc-emp-%'");
        jdbc.update("DELETE FROM hr_skill_matrix WHERE EMP_ID LIKE 'tpc-emp-%'");
        jdbc.update("DELETE FROM hr_train_task WHERE PLAN_ID IN "
                + "(SELECT ID FROM hr_train_plan WHERE PLAN_NO LIKE 'TP2098%' OR PLAN_NO LIKE 'TP2099%')");
        jdbc.update("DELETE FROM hr_train_plan WHERE PLAN_NO LIKE 'TP2098%' OR PLAN_NO LIKE 'TP2099%'");
        jdbc.update("DELETE FROM hr_employee WHERE ID LIKE 'tpc-emp-%' OR EMP_NO LIKE 'TPC-%'");
        jdbc.update("DELETE FROM hr_dept WHERE ID='tdept-train'");
    }

    private void seedEmp(String suffix) {
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, EMP_STATUS, HIRE_DATE, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, ?, ?, 'REGULAR', ?, 'junit', '0', 0)",
                "tpc-emp-" + suffix, "TPC-" + suffix.toUpperCase(), "培训员工" + suffix, "TPCID-" + suffix,
                LocalDate.now().minusYears(1));
    }

    @SuppressWarnings("unchecked")
    private com.erp.entity.hr.HrTrainPlan createDeptPlan() {
        Map<String, Object> payload = Map.of("courseName", "测试安全课", "trainType", "内训",
                "targetType", "DEPT", "targetValue", "tdept-train", "planYear", 2099,
                "dueDate", LocalDate.now().plusDays(10).toString(), "planHours", 8,
                "skillCode", "TSKTR", "skillLevel", "L1");
        return (com.erp.entity.hr.HrTrainPlan) planService.create(payload).get("plan");
    }

    private com.erp.entity.hr.HrTrainPlan createMembersPlan(String course, String members, Object... extra) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("courseName", course);
        payload.put("targetType", "MEMBERS");
        payload.put("targetValue", members);
        payload.put("planYear", 2099);
        payload.put("dueDate", LocalDate.now().plusDays(5).toString());
        for (int i = 0; i + 1 < extra.length; i += 2) {
            payload.put(String.valueOf(extra[i]), extra[i + 1]);
        }
        return (com.erp.entity.hr.HrTrainPlan) planService.create(payload).get("plan");
    }

    @Test
    void createExpandIdempotentAndChangeLog() {
        var plan = createDeptPlan();
        assertTrue(plan.getPlanNo().startsWith("TP2099"));
        assertEquals("PUBLISHED", plan.getStatus());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM hr_train_task WHERE PLAN_ID=?",
                Integer.class, plan.getId()), "部门 2 人展开");
        // 重复保存（对象未变）幂等
        var upd = planService.update(plan.getId(), Map.of("targetType", "DEPT", "targetValue", "tdept-train"));
        assertEquals(0, upd.get("expanded"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM hr_train_task WHERE PLAN_ID=?",
                Integer.class, plan.getId()));
        // 对象追加人员 → 只补新任务
        var upd2 = planService.update(plan.getId(), Map.of("targetType", "MEMBERS",
                "targetValue", "tpc-emp-a,tpc-emp-b,tpc-emp-solo"));
        assertEquals(1, upd2.get("expanded"));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM hr_train_task WHERE PLAN_ID=?",
                Integer.class, plan.getId()));
        // 变更留痕
        var upd3 = planService.update(plan.getId(), Map.of("courseName", "测试安全课改名"));
        var updated = (com.erp.entity.hr.HrTrainPlan) upd3.get("plan");
        assertNotNull(updated.getChangeLogJson());
        assertTrue(updated.getChangeLogJson().contains("courseName"));
    }

    @Test
    void cancelClosesTasks() {
        var plan = createDeptPlan();
        planService.cancel(plan.getId());
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT STATUS FROM hr_train_plan WHERE ID=?",
                String.class, plan.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM hr_train_task WHERE PLAN_ID=? "
                + "AND STATUS<>'CANCELLED'", Integer.class, plan.getId()));
        assertThrows(ServiceException.class, () ->
                planService.update(plan.getId(), Map.of("courseName", "x")));
    }

    @Test
    void confirmSelfOnly() {
        var plan = createMembersPlan("测试确认课", "tpc-emp-solo");
        String taskId = jdbc.queryForObject("SELECT ID FROM hr_train_task WHERE PLAN_ID=?", String.class, plan.getId());
        // 非本人 → 403
        assertThrows(ServiceException.class, () -> planService.confirmTask(taskId, "tpc-emp-a"));
        // 本人确认 → CONFIRMED SELF，幂等
        Map<String, Object> out = planService.confirmTask(taskId, "tpc-emp-solo");
        assertEquals("CONFIRMED", ((com.erp.entity.hr.HrTrainTask) out.get("task")).getStatus());
        planService.confirmTask(taskId, "tpc-emp-solo");
        assertEquals("SELF", jdbc.queryForObject("SELECT CONFIRM_SRC FROM hr_train_task WHERE ID=?",
                String.class, taskId));
        // HR 登记完成后再确认 → 422（完成判定以 HR 为准）
        planService.registerResult(taskId, Map.of("score", 90, "attend", "正常"));
        assertThrows(ServiceException.class, () -> planService.confirmTask(taskId, "tpc-emp-solo"));
    }

    @Test
    void resultAndMatrixLinkage() {
        var plan = createMembersPlan("测试技能课", "tpc-emp-solo", "skillCode", "TSKTR2", "skillLevel", "L2",
                "passScore", 60);
        String taskId = jdbc.queryForObject("SELECT ID FROM hr_train_task WHERE PLAN_ID=?", String.class, plan.getId());
        // 达线 → COMPLETED + 矩阵 TRAIN ACTIVE
        var out = planService.registerResult(taskId, Map.of("score", 85, "attend", "正常", "actualHours", 8));
        assertEquals("COMPLETED", ((com.erp.entity.hr.HrTrainTask) out.get("task")).getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM hr_skill_matrix WHERE EMP_ID='tpc-emp-solo' "
                + "AND SKILL_CODE='TSKTR2' AND SOURCE='TRAIN' AND STATUS='ACTIVE'", Integer.class));
        // 改判不达线 → FAILED + 矩阵 TRAIN EXPIRED + 留痕
        var out2 = planService.registerResult(taskId, Map.of("score", 40, "attend", "迟到"));
        assertEquals("FAILED", ((com.erp.entity.hr.HrTrainTask) out2.get("task")).getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM hr_skill_matrix WHERE EMP_ID='tpc-emp-solo' "
                + "AND SKILL_CODE='TSKTR2' AND STATUS='EXPIRED'", Integer.class));
        String hist = jdbc.queryForObject("SELECT RESULT_HIST_JSON FROM hr_train_task WHERE ID=?",
                String.class, taskId);
        assertTrue(hist.contains("newScore"), hist);
        // 未达线分数缺失 → 422
        assertThrows(ServiceException.class, () -> planService.registerResult(taskId, Map.of("attend", "正常")));
    }

    @Test
    void ratesAndStats() {
        var plan = createDeptPlan();
        String planId = plan.getId();
        var tasks = jdbc.queryForList("SELECT ID, EMP_ID FROM hr_train_task WHERE PLAN_ID=?", planId);
        // a 达线、b 未达线、solo 作废（不计分母）
        planService.registerResult(tasks.get(0).get("ID").toString(), Map.of("score", 85));
        planService.registerResult(tasks.get(1).get("ID").toString(), Map.of("score", 30));
        jdbc.update("UPDATE hr_train_task SET STATUS='CANCELLED' WHERE PLAN_ID=? AND EMP_ID='tpc-emp-solo'",
                planId);
        Map<String, Object> detail = planService.detail(planId);
        Map<String, Object> stats = (Map<String, Object>) detail.get("stats");
        assertEquals(2, stats.get("should"));
        assertEquals(1, stats.get("completed"));
        assertEquals(0, new BigDecimal("50.00").compareTo(new BigDecimal(String.valueOf(stats.get("rate")))));
        // 部门/年度完成率
        var rates = planService.rates(2099, "tdept-train");
        assertEquals(1, rates.size());
        assertEquals(1, ((Number) rates.get(0).get("completed")).intValue());
        assertEquals(0, new BigDecimal("50.00").compareTo(new BigDecimal(String.valueOf(rates.get(0).get("rate")))));
    }

    @Test
    void remindScanIdempotentAndOverdue() {
        // 临期：期限 today+2（≤3 天窗口）
        var plan1 = createMembersPlan("催办临期课", "tpc-emp-solo", "dueDate", LocalDate.now().plusDays(2).toString());
        // 逾期：期限 today-1
        var plan2 = createMembersPlan("催办逾期课", "tpc-emp-a", "dueDate", LocalDate.now().minusDays(1).toString());
        planService.remindScan();
        // hr_remind 各一条（UK 防重：同日重扫不重复）
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM hr_remind WHERE REMIND_TYPE='TRAIN_TASK' "
                + "AND EMP_ID='tpc-emp-solo'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM hr_remind WHERE REMIND_TYPE='TRAIN_TASK' "
                + "AND EMP_ID='tpc-emp-a'", Integer.class));
        planService.remindScan();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM hr_remind WHERE REMIND_TYPE='TRAIN_TASK' "
                + "AND EMP_ID='tpc-emp-solo'", Integer.class));
        // 逾期明细文案
        String detail = jdbc.queryForObject("SELECT DETAIL FROM hr_remind WHERE REMIND_TYPE='TRAIN_TASK' "
                + "AND EMP_ID='tpc-emp-a'", String.class);
        assertTrue(detail.contains("逾期"), detail);
        assertNotNull(plan1.getPlanNo());
        assertNotNull(plan2.getPlanNo());
    }
}
