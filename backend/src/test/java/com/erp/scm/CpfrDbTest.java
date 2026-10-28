package com.erp.scm;

import com.erp.common.ServiceException;
import com.erp.entity.scm.CpfrAgreement;
import com.erp.service.scm.CpfrAccuracyService;
import com.erp.service.scm.CpfrAgreementService;
import com.erp.service.scm.CpfrClockService;
import com.erp.service.scm.CpfrDeadlineService;
import com.erp.service.scm.CpfrExceptionService;
import com.erp.service.scm.CpfrFeedbackService;
import com.erp.service.scm.CpfrForecastService;
import com.erp.service.scm.CpfrPlanService;
import com.erp.service.impl.scm.CpfrPlanServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CPFR DbTest（add-cpfr-forecast-replenishment，spec cpfr-collaboration R1-R7）：
 * BR-4.9-31 参数缺失禁启用、BR-4.9-33 48h/72h 惰性判定幂等 + 默认采纳、
 * BR-4.9-32 分歧例外、ZONE 边界（CpfrPlanServiceImpl.zoneOf）、准确率公式与待复盘。
 * 造数前缀 ccf-，物理 DELETE 清理（唯一键不区分逻辑删除的教训）。
 */
@SpringBootTest
class CpfrDbTest {

    @Autowired
    private CpfrAgreementService agreementService;
    @Autowired
    private CpfrForecastService forecastService;
    @Autowired
    private CpfrFeedbackService feedbackService;
    @Autowired
    private CpfrDeadlineService deadlineService;
    @Autowired
    private CpfrExceptionService exceptionService;
    @Autowired
    private CpfrPlanService planService;
    @Autowired
    private CpfrAccuracyService accuracyService;
    @Autowired
    private CpfrClockService clockService;
    @Autowired
    private JdbcTemplate jdbc;

    private final String supplierId = "ccf-sup-" + UUID.randomUUID().toString().substring(0, 8);
    private String agreementId;
    private String headId;

    @BeforeEach
    void setUp() {
        purge();
        clockService.setClock(Clock.fixed(Instant.parse("2026-10-28T02:00:00Z"), ZoneId.of("Asia/Shanghai")));
        Map<String, Object> agr = agreementService.save(Map.of(
                "supplierId", supplierId, "supplierName", "CCF 测试供应商",
                "cycleType", "WEEK", "horizonWeeks", 8,
                "freezeWeeks", 2, "elasticStartWeek", 3, "elasticEndWeek", 6,
                "elasticPct", 20, "deviationThresholdPct", 10));
        agreementId = String.valueOf(agr.get("id"));
    }

    @AfterEach
    void tearDown() {
        clockService.setClock(Clock.systemDefaultZone());
        purge();
    }

    // ---------- R1：协议参数与启用管控 ----------

    @Test
    void enableBlockedWhenParamsMissing() {
        // 清空冻结期 → 禁启用（BR-4.9-31，L1）
        jdbc.update("UPDATE erp_scm_cpfr_agreement SET FREEZE_WEEKS = NULL WHERE ID = ?", agreementId);
        ServiceException e = assertThrows(ServiceException.class, () -> agreementService.enable(agreementId));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("冻结期"), "缺失清单含冻结期：" + e.getMessage());
        // 状态保持未启用
        String st = jdbc.queryForObject(
                "SELECT STATUS FROM erp_scm_cpfr_agreement WHERE ID=?", String.class, agreementId);
        assertEquals(CpfrAgreement.ST_INIT, st);
    }

    @Test
    void enableAndExpirePauseLifecycle() {
        agreementService.enable(agreementId);
        assertEquals(CpfrAgreement.ST_ACTIVE, agreementService.get(agreementId).get("status"));
        // 到期日 = 今天 → 惰性判定暂停（BR-4.9-34）
        jdbc.update("UPDATE erp_scm_cpfr_agreement SET EXPIRE_DATE = '2026-10-28' WHERE ID=?", agreementId);
        Map<String, Object> m = agreementService.get(agreementId);
        assertEquals(CpfrAgreement.ST_PAUSED, m.get("status"));
        assertEquals(CpfrAgreement.REASON_EXPIRE, m.get("pauseReason"));
    }

    // ---------- R3：48h/72h 惰性判定幂等 + 默认采纳 ----------

    @Test
    void deadlineEvaluate48hRemind72hEscalateDefaultRoll() {
        seedForecastAndPush();
        // 周期开始 = 49 小时前 → 催办（幂等：两次仅一条）
        LocalDateTime start = LocalDateTime.of(2026, 10, 26, 1, 0);
        jdbc.update("UPDATE erp_scm_cpfr_feedback SET PERIOD_START_AT = ? WHERE AGREEMENT_ID = ?",
                start, agreementId);
        Map<String, Object> r1 = deadlineService.evaluate(agreementId, "209901");
        assertEquals(2, r1.get("reminded"), "49h 首次推进：两行反馈均催办（按行推进）");
        Map<String, Object> r2 = deadlineService.evaluate(agreementId, "209901");
        assertEquals(0, r2.get("reminded"), "重复 evaluate 幂等不重复催办");
        Integer remindCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_scm_cpfr_feedback WHERE AGREEMENT_ID=? AND REMIND_AT IS NOT NULL",
                Integer.class, agreementId);
        assertEquals(2, remindCnt);

        // 推到 73 小时 → 升级 PMGR + 默认采纳（上轮量 300）
        seedPriorRoundSupply(new BigDecimal("300"));
        jdbc.update("UPDATE erp_scm_cpfr_feedback SET PERIOD_START_AT = ? WHERE AGREEMENT_ID = ?",
                LocalDateTime.of(2026, 10, 25, 1, 0), agreementId);
        Map<String, Object> r3 = deadlineService.evaluate(agreementId, "209901");
        assertEquals(2, r3.get("escalated"), "73h 两行均升级 PMGR");
        Map<String, Object> fb = jdbc.queryForMap(
                "SELECT STATUS, SUPPLY_QTY, FEEDBACK_SOURCE, ESCALATE_TO_ROLE FROM erp_scm_cpfr_feedback "
                        + "WHERE AGREEMENT_ID=? AND PERIOD_TAG='209901' AND ITEM_CODE='CCF-ITEM-A'", agreementId);
        assertEquals("默认采纳", fb.get("STATUS"));
        assertEquals(0, new BigDecimal(String.valueOf(fb.get("SUPPLY_QTY")))
                .compareTo(new BigDecimal("300")), "默认采纳取上轮量 300");
        assertEquals("DEFAULT_ROLL", fb.get("FEEDBACK_SOURCE"));
        assertEquals("ROLE_PMGR", fb.get("ESCALATE_TO_ROLE"));
    }

    // ---------- R2/R4：数据包快照 + 分歧例外 ----------

    @Test
    void pushGeneratesFeedbackAndDeviationCreatesException() {
        seedForecastAndPush();
        // 反馈行已按预测行生成（待反馈）
        Integer fbCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_scm_cpfr_feedback WHERE AGREEMENT_ID=? AND PERIOD_TAG='209901'",
                Integer.class, agreementId);
        assertEquals(2, fbCnt);
        // 供给侧 850/1000 = -15% > ±10% → 例外（BR-4.9-32）
        feedbackService.submit(supplierId, Map.of(
                "agreementId", agreementId, "periodTag", "209901",
                "weekOffset", 1, "itemCode", "CCF-ITEM-A", "supplyQty", 850));
        var exceptions = exceptionService.list(agreementId, null);
        assertEquals(1, exceptions.size());
        assertEquals("待协商", exceptions.get(0).get("status"));
        // 另一行 1050/1000 = +5% 阈值内 → 无例外
        feedbackService.submit(supplierId, Map.of(
                "agreementId", agreementId, "periodTag", "209901",
                "weekOffset", 2, "itemCode", "CCF-ITEM-B", "supplyQty", 1050));
        assertEquals(1, exceptionService.list(agreementId, null).size(), "阈值内不新增例外");
    }

    // ---------- R6：ZONE 边界 ----------

    @Test
    void zoneBoundaryFreezeElasticFree() {
        CpfrAgreement agr = jdbc.queryForObject(
                "SELECT * FROM erp_scm_cpfr_agreement WHERE ID=?", (rs, i) -> {
                    CpfrAgreement a = new CpfrAgreement();
                    a.setFreezeWeeks(rs.getInt("FREEZE_WEEKS"));
                    a.setElasticStartWeek(rs.getInt("ELASTIC_START_WEEK"));
                    a.setElasticEndWeek(rs.getInt("ELASTIC_END_WEEK"));
                    return a;
                }, agreementId);
        assertEquals("FREEZE", CpfrPlanServiceImpl.zoneOf(1, agr), "W1 ≤ 冻结 2 周");
        assertEquals("FREEZE", CpfrPlanServiceImpl.zoneOf(2, agr), "W2 = 冻结边界");
        assertEquals("ELASTIC", CpfrPlanServiceImpl.zoneOf(3, agr), "W3 弹性起始");
        assertEquals("ELASTIC", CpfrPlanServiceImpl.zoneOf(6, agr), "W6 弹性边界");
        assertEquals("FREE", CpfrPlanServiceImpl.zoneOf(7, agr), "W7 自由区");
    }

    // ---------- R5/R7：未达成例外阻断 + 准确率 ----------

    @Test
    void openExceptionBlocksConfirmAndAccuracyTriggersReview() {
        seedForecastAndPush();
        feedbackService.submit(supplierId, Map.of(
                "agreementId", agreementId, "periodTag", "209901",
                "weekOffset", 1, "itemCode", "CCF-ITEM-A", "supplyQty", 850));
        // 创建计划（含例外计数）
        Map<String, Object> plan = planService.create(Map.of(
                "agreementId", agreementId, "periodTag", "209901"));
        String planId = String.valueOf(plan.get("id"));
        // 未达成例外 → 确认阻断（422）
        ServiceException e = assertThrows(ServiceException.class,
                () -> planService.confirm(planId, "INTERNAL", "tester"));
        assertEquals(422, e.getCode());
        // 例外终确认达成 → 双方确认通过
        var ex = exceptionService.list(agreementId, null).get(0);
        exceptionService.finalConfirm(String.valueOf(ex.get("id")), true, "接受 850");
        planService.confirm(planId, "INTERNAL", "tester");
        Map<String, Object> confirmed = jdbc.queryForMap(
                "SELECT STATUS, INTERNAL_CONFIRM_BY FROM erp_scm_cpfr_plan WHERE ID=?", planId);
        assertEquals("单方确认", confirmed.get("STATUS"));

        // 准确率公式（两行口径）：W1 1000/990、W2 1000/1010 → 1−(10+10)/2000 = 99%
        jdbc.update("UPDATE erp_scm_cpfr_plan SET STATUS='已生成订单' WHERE ID=?", planId);
        jdbc.update("UPDATE erp_scm_cpfr_plan_line SET PO_CONVERSION_QTY = CASE WEEK_OFFSET "
                        + "WHEN 1 THEN 990 WHEN 2 THEN 1010 END WHERE PLAN_ID=?", planId);
        Map<String, Object> out = accuracyService.evaluateMonth("209910");
        assertEquals(1, out.get("plans"));
        BigDecimal acc = jdbc.queryForObject(
                "SELECT ACCURACY_PCT FROM erp_scm_cpfr_plan WHERE ID=?", BigDecimal.class, planId);
        assertEquals(0, acc.compareTo(new BigDecimal("99.00")), "准确率 = 1−(10+10)/2000 = 99%");
    }

    // ---------- 造数辅助 ----------

    /** 建预测版本 + 两行（A W1=1000 / B W2=1000）+ 生成数据包 + 推送 */
    private void seedForecastAndPush() {
        Map<String, Object> head = forecastService.createHead(Map.of("periodTag", "209901"));
        headId = String.valueOf(head.get("id"));
        forecastService.saveLine(Map.of("headId", headId, "agreementId", agreementId,
                "itemCode", "CCF-ITEM-A", "weekOffset", 1, "forecastQty", 1000));
        forecastService.saveLine(Map.of("headId", headId, "agreementId", agreementId,
                "itemCode", "CCF-ITEM-B", "weekOffset", 2, "forecastQty", 1000));
        forecastService.generatePackage(headId);
        // 快照字段落库
        Map<String, Object> line = jdbc.queryForMap(
                "SELECT SNAPSHOT_STOCK, SNAPSHOT_ONHAND_PO, SAFETY_STOCK FROM erp_scm_cpfr_forecast_line "
                        + "WHERE HEAD_ID=? AND ITEM_CODE='CCF-ITEM-A'", headId);
        assertNotNull(line.get("SNAPSHOT_STOCK"), "库存快照已写入");
        assertNotNull(line.get("SNAPSHOT_ONHAND_PO"), "在途 PO 快照已写入");
        assertNotNull(line.get("SAFETY_STOCK"), "安全库存快照已写入");
        // 启用后推送（未启用 422 已由其它用例覆盖）
        agreementService.enable(agreementId);
        forecastService.pushCycle(headId);
    }

    /** 造上一轮（209852）同 SKU 同周反馈量 */
    private void seedPriorRoundSupply(BigDecimal qty) {
        jdbc.update("INSERT INTO erp_scm_cpfr_feedback "
                        + "(ID, AGREEMENT_ID, PERIOD_TAG, WEEK_OFFSET, ITEM_CODE, DEMAND_QTY, SUPPLY_QTY, "
                        + " STATUS, FEEDBACK_SOURCE, SUBMIT_AT, PERIOD_START_AT, CREATE_BY, DEL_FLAG, VER_NO) "
                        + "VALUES (?, ?, '209852', 1, 'CCF-ITEM-A', 1000, ?, '已反馈', 'PORTAL', "
                        + " '2026-10-20 10:00:00', '2026-10-19 01:00:00', 'ccf-emp', '0', 0)",
                "ccf-fb-prev-" + UUID.randomUUID().toString().substring(0, 8), agreementId, qty);
    }

    /** 物理清理（唯一键不区分逻辑删除 → DELETE 不用软删；按表结构分别清理） */
    private void purge() {
        String agrSub = "(SELECT ID FROM erp_scm_cpfr_agreement WHERE SUPPLIER_ID LIKE 'ccf-sup-%')";
        // 有 AGREEMENT_ID 列的表按协议子查询清理
        for (String tbl : new String[]{"erp_scm_cpfr_plan_line", "erp_scm_cpfr_plan",
                "erp_scm_cpfr_exception", "erp_scm_cpfr_adjust_log", "erp_scm_cpfr_feedback",
                "erp_scm_cpfr_forecast_line"}) {
            jdbc.execute("DELETE FROM " + tbl + " WHERE AGREEMENT_ID IN " + agrSub);
        }
        // forecast_head 无 AGREEMENT_ID：按测试周期标签清理
        jdbc.execute("DELETE FROM erp_scm_cpfr_forecast_head WHERE PERIOD_TAG = '209901'");
        jdbc.execute("DELETE FROM erp_scm_cpfr_agreement WHERE SUPPLIER_ID LIKE 'ccf-sup-%'");
    }
}
