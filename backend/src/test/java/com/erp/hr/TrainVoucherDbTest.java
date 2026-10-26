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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 培训费用计提凭证 DbTest（training-plan-execution）：TRAINING 锚点生成/幂等/
 * 金额变动红冲重开/清零冲销/作废冲销/缺科目拒绝。
 * 造数前缀 tvx- / 计划 TP2098（年份隔离，不与 smoke 2026 锚点冲突）。
 */
@SpringBootTest
class TrainVoucherDbTest {

    @Autowired
    private HrTrainPlanService planService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, EMP_STATUS, HIRE_DATE, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES ('tvx-emp-a', 'TVX-A', '凭证员工A', 'TVXID-a', "
                + "'REGULAR', ?, 'junit', '0', 0)", LocalDate.now().minusYears(1));
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    private void purge() {
        jdbc.update("DELETE l FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON l.VOUCHER_ID=v.ID "
                + "WHERE v.SOURCE_TYPE='TRAINING'");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='TRAINING'");
        jdbc.update("DELETE FROM hr_train_task WHERE PLAN_ID IN "
                + "(SELECT ID FROM hr_train_plan WHERE PLAN_NO LIKE 'TP2098%')");
        jdbc.update("DELETE FROM hr_train_plan WHERE PLAN_NO LIKE 'TP2098%'");
        jdbc.update("DELETE FROM hr_employee WHERE ID LIKE 'tvx-emp-%'");
    }

    private Map<String, Object> createPlan() {
        var out = planService.create(Map.of("courseName", "凭证测试课", "targetType", "MEMBERS",
                "targetValue", "tvx-emp-a", "planYear", 2098));
        return out;
    }

    /** 锚点下未冲销凭证的 Dr 合计 */
    private BigDecimal activeDrSum(String anchor) {
        BigDecimal sum = jdbc.queryForObject("SELECT COALESCE(SUM(l.AMOUNT),0) FROM erp_fin_voucher_line l "
                        + "JOIN erp_fin_voucher v ON l.VOUCHER_ID=v.ID "
                        + "WHERE v.SOURCE_TYPE='TRAINING' AND v.SOURCE_DOC_NO=? AND l.DIRECTION='Dr' "
                        + "AND v.REVERSES_ID IS NULL "
                        + "AND v.ID NOT IN (SELECT REVERSES_ID FROM erp_fin_voucher WHERE REVERSES_ID IS NOT NULL)",
                BigDecimal.class, anchor);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    @Test
    void generateIdempotentAndUpdateReverses() {
        var plan = (com.erp.entity.hr.HrTrainPlan) createPlan().get("plan");
        String anchor = plan.getPlanNo() + "@2098";
        // 首次登记 5000 → 新建
        var out1 = planService.registerCost(plan.getId(), Map.of("actualCost", 5000));
        assertEquals(1, ((Number) out1.get("action")).intValue(), "首次新建：" + out1);
        assertEquals(0, new BigDecimal("5000").compareTo(activeDrSum(anchor)));
        // 科目正确：借 660105 / 贷 221103
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM erp_fin_voucher_line l "
                + "JOIN erp_fin_voucher v ON l.VOUCHER_ID=v.ID WHERE v.SOURCE_TYPE='TRAINING' "
                + "AND v.SOURCE_DOC_NO=? AND l.ACCOUNT_CODE='660105' AND l.DIRECTION='Dr'", Integer.class, anchor));
        // 同额重复登记 → 无变化
        var out2 = planService.registerCost(plan.getId(), Map.of("actualCost", 5000));
        assertEquals(0, ((Number) out2.get("action")).intValue(), "同额幂等：" + out2);
        // 金额变动 5000→6000 → 红冲重开，活动 Dr 合计=6000（账面不重复）
        var out3 = planService.registerCost(plan.getId(), Map.of("actualCost", 6000));
        assertEquals(2, ((Number) out3.get("action")).intValue(), "变动红冲重开：" + out3);
        assertEquals(0, new BigDecimal("6000").compareTo(activeDrSum(anchor)));
        // 冲销凭证存在（REVERSES_ID 关联）
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='TRAINING' "
                + "AND REVERSES_ID IS NOT NULL", Integer.class) >= 1);
    }

    @Test
    void zeroCostClearsAndCancelReverses() {
        var plan = (com.erp.entity.hr.HrTrainPlan) createPlan().get("plan");
        String anchor = plan.getPlanNo() + "@2098";
        planService.registerCost(plan.getId(), Map.of("actualCost", 2000));
        assertEquals(0, new BigDecimal("2000").compareTo(activeDrSum(anchor)));
        // 清零 → 冲销
        var zero = planService.registerCost(plan.getId(), Map.of("actualCost", 0));
        assertEquals(3, ((Number) zero.get("action")).intValue(), "清零冲销：" + zero);
        assertEquals(0, activeDrSum(anchor).compareTo(BigDecimal.ZERO));
        // 再登记 3000 → 新建；作废计划 → 全部冲销
        planService.registerCost(plan.getId(), Map.of("actualCost", 3000));
        assertEquals(0, new BigDecimal("3000").compareTo(activeDrSum(anchor)));
        planService.cancel(plan.getId());
        assertEquals(0, activeDrSum(anchor).compareTo(BigDecimal.ZERO), "作废冲销后活动凭证为零");
    }

    @Test
    void missingAccountRejects() {
        var plan = (com.erp.entity.hr.HrTrainPlan) createPlan().get("plan");
        // 临时删除科目映射 → 登记被拒（5000 科目缺失 L1），事务回滚不留费用
        jdbc.update("DELETE FROM hr_pay_account_mapping WHERE ID='PAM-TRAIN-EXP'");
        try {
            assertThrows(ServiceException.class, () ->
                    planService.registerCost(plan.getId(), Map.of("actualCost", 5000)));
            BigDecimal cost = jdbc.queryForObject("SELECT ACTUAL_COST FROM hr_train_plan WHERE ID=?",
                    BigDecimal.class, plan.getId());
            assertTrue(cost == null, "缺科目拒绝后费用不落库：" + cost);
        } finally {
            jdbc.update("INSERT IGNORE INTO hr_pay_account_mapping (ID, MAPPING_TYPE, MAPPING_KEY, "
                    + "ACCOUNT_CODE, REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                    + "VALUES ('PAM-TRAIN-EXP', 'FIXED', 'TRAIN_EXPENSE', '660105', "
                    + "'培训计提：管理费用-职工教育经费', 'migration', NOW(), '0', 0)");
        }
    }
}
