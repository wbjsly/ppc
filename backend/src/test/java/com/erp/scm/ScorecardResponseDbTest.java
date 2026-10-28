package com.erp.scm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记分卡响应维度三子项 DbTest（add-supply-performance-menu，spec supplier-scorecard MODIFIED）：
 * PO48h + CPFR 反馈及时率（48h 全量口径，默认采纳进分母不进分子）+ 异常工单响应折算，
 * 三等权 + 缺失回退；明细键 cpfrRate 语义真实、VMI 落 replenishRate。
 * 造数前缀 sc9-，月份 209912 隔离，物理 DELETE 清理。
 */
@SpringBootTest
class ScorecardResponseDbTest {

    private static final String MONTH = "209912";
    private static final String SUP1 = "SC9-SUP1";
    private static final String SUP2 = "SC9-SUP2";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.erp.service.scm.ScorecardService scorecardService;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        purge();
        // 保证有生效模型（共享库可能已有；无则造一个）
        Integer models = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_scm_scorecard_model WHERE STATUS='ACTIVE' AND DEL_FLAG='0'",
                Integer.class);
        if (models == null || models == 0) {
            jdbc.update("INSERT INTO erp_scm_scorecard_model (ID,MODEL_CODE,NAME,W_QUALITY,W_DELIVERY,"
                    + "W_COST,W_RESPONSE,COST_INDEX_WEIGHT,THRESH_A,THRESH_B,THRESH_C,VERSION,STATUS,"
                    + "CREATE_BY,DEL_FLAG,VER_NO) VALUES ('sc9-model','SC9','SC9 冒烟模型',30,30,20,20,"
                    + "60,90,75,60,1,'ACTIVE','sc9-emp','0',0)");
        }
        // SUP1：活跃供应商（窗口内 PO）+ 及时 PO 确认事件（48h 内）
        insertPo("SC9-PO-1", SUP1, "2099-12-05 10:00:00");
        insertPoConfirmEvent("SC9-PO-1", "2099-12-06 10:00:00");
        // SUP1：CPFR 协议 + 反馈 2 行（1 及时 / 1 默认采纳）→ cpfrRate = 50
        jdbc.update("INSERT INTO erp_scm_cpfr_agreement (ID,SUPPLIER_ID,SUPPLIER_NAME,CYCLE_TYPE,"
                + "HORIZON_WEEKS,DEVIATION_THRESHOLD_PCT,STATUS,CREATE_BY,DEL_FLAG,VER_NO) "
                + "VALUES ('sc9-agr1',?,'SC9 供应商一','WEEK',8,10,'已启用','sc9-emp','0',0)", SUP1);
        jdbc.update("INSERT INTO erp_scm_cpfr_feedback (ID,AGREEMENT_ID,PERIOD_TAG,WEEK_OFFSET,ITEM_CODE,"
                + "DEMAND_QTY,SUPPLY_QTY,FEEDBACK_SOURCE,STATUS,SUBMIT_AT,PERIOD_START_AT,CREATE_BY,DEL_FLAG,VER_NO) "
                + "VALUES ('sc9-fb-timely','sc9-agr1','209912',1,'SC9-ITEM',100,100,'PORTAL','已反馈',"
                + "'2099-12-03 01:00:00','2099-12-03 00:00:00','sc9-emp','0',0), "
                + "('sc9-fb-default','sc9-agr1','209912',2,'SC9-ITEM',100,100,'DEFAULT_ROLL','默认采纳',"
                + "'2099-12-07 00:00:00','2099-12-03 00:00:00','sc9-emp','0',0)");
        // SUP1：2 张已解锁工单（6h / 18h）→ avg 12h → tkScore = 100
        insertTicket("sc9-tk-1", SUP1, "SC9-PO-1", "2099-12-10 08:00:00", "2099-12-10 14:00:00");
        insertTicket("sc9-tk-2", SUP1, "SC9-PO-1", "2099-12-11 08:00:00", "2099-12-12 02:00:00");
        // SUP2：仅 PO（48h 内无确认事件）→ poRate=0；无 CPFR 无工单 → 缺失回退
        insertPo("SC9-PO-2", SUP2, "2099-12-05 10:00:00");
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    @Test
    void responseThreeSubItemsEqualWeight() throws Exception {
        Map<String, Object> out = scorecardService.collectMonth(MONTH);
        assertNotNull(out);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT R_SCORE, SOURCE_JSON FROM erp_scm_scorecard_result "
                        + "WHERE MONTH_TAG=? AND SUPPLIER_ID=? AND DEL_FLAG='0'", MONTH, SUP1);
        // 三子项：PO48h=100（48h 内确认事件）、CPFR=50（1/2）、工单折算=100（avg 12h ≤ 24h）
        BigDecimal rScore = new BigDecimal(String.valueOf(row.get("R_SCORE")));
        assertEquals(0, rScore.compareTo(new BigDecimal("83.33")), "三等权 (100+50+100)/3=83.33");
        JsonNode src = mapper.readTree(String.valueOf(row.get("SOURCE_JSON")));
        assertEquals(100.0, src.get("po48hRate").asDouble(), 0.01, "po48hRate");
        assertEquals(50.0, src.get("cpfrRate").asDouble(), 0.01, "cpfrRate 真实 CPFR 口径（默认采纳进分母）");
        assertEquals(12.0, src.get("ticketRespAvgHours").asDouble(), 0.01, "工单平均响应 12h");
        assertEquals(100.0, src.get("ticketScore").asDouble(), 0.01, "12h ≤ 24h → 100 分");
    }

    @Test
    void missingSubItemsFallBackToAvailable() throws Exception {
        scorecardService.collectMonth(MONTH);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT R_SCORE, SOURCE_JSON FROM erp_scm_scorecard_result "
                        + "WHERE MONTH_TAG=? AND SUPPLIER_ID=? AND DEL_FLAG='0'", MONTH, SUP2);
        // 仅 poRate 可用（=0，无确认事件）→ rScore = 0；CPFR/工单子项记 missing
        BigDecimal rScore = new BigDecimal(String.valueOf(row.get("R_SCORE")));
        assertEquals(0, rScore.compareTo(BigDecimal.ZERO), "唯一可用子项 = poRate = 0");
        JsonNode src = mapper.readTree(String.valueOf(row.get("SOURCE_JSON")));
        String srcText = String.valueOf(row.get("SOURCE_JSON"));
        assertTrue(srcText.contains("SC_RESPONSE_CPFR"), "CPFR 子项缺失标记：" + srcText);
        assertTrue(srcText.contains("SC_RESPONSE_TICKET"), "工单子项缺失标记：" + srcText);
        assertTrue(src.path("cpfrRate").isNull(), "无 CPFR 数据时 cpfrRate 为 null");
    }

    @Test
    void vmiReplenishRateLandsInDetailKeyOnly() throws Exception {
        // 造 VMI 补货确认行——MUST NOT 进响应维度，仅落 replenishRate 明细
        jdbc.update("INSERT INTO erp_proc_replenish_confirm (ID,ALERT_ID,SUPPLIER_ID,ITEM_CODE,"
                + "SUGGEST_QTY,CONFIRM_QTY,SOURCE,CONFIRM_AT,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO) "
                + "VALUES ('sc9-vmi-1','sc9-alert-1',?,'SC9-ITEM',100,100,'PORTAL',"
                + "'2099-12-06 10:00:00','sc9-emp','2099-12-05 10:00:00','0',0)", SUP1);
        scorecardService.collectMonth(MONTH);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT SOURCE_JSON FROM erp_scm_scorecard_result "
                        + "WHERE MONTH_TAG=? AND SUPPLIER_ID=? AND DEL_FLAG='0'", MONTH, SUP1);
        JsonNode src = mapper.readTree(String.valueOf(row.get("SOURCE_JSON")));
        // cpfrRate 仍是 CPFR 真值 50（VMI 行 24h 内确认及时率 100 不得覆盖）
        assertEquals(50.0, src.get("cpfrRate").asDouble(), 0.01, "cpfrRate 不被 VMI 数据冒充");
        assertEquals(100.0, src.get("replenishRate").asDouble(), 0.01, "VMI 值落 replenishRate 明细");
    }

    // ---------- 造数辅助 ----------

    private void insertPo(String poNo, String supplierId, String createAt) {
        jdbc.update("INSERT INTO erp_proc_po (ID,PO_NO,PO_TYPE,SOURCE,SUPPLIER_ID,SUPPLIER_NAME,STATUS,"
                + "CREATE_DATE,CREATE_BY,DEL_FLAG,VER_NO) VALUES (?,?,?,?,?,?,'APPROVED',?,'sc9-emp','0',0)",
                "sc9-po-" + poNo, poNo, "NORMAL", "PR", supplierId, "SC9 供应商", createAt);
    }

    private void insertPoConfirmEvent(String poNo, String occurredAt) {
        jdbc.update("INSERT INTO erp_ops_outbox (ID,EVENT_ID,EVENT_TYPE,VERSION,RECORD_VERSION,OCCURRED_AT,"
                + "SOURCE,IDEMPOTENCY_KEY,PAYLOAD,STATUS,DEL_FLAG,VER_NO) VALUES "
                + "(?,?,'PROC.PO_CONFIRMED',1,1,?,'proc',?,?,?,'0',0)",
                "sc9-evt-" + poNo, "sc9-event-" + poNo, occurredAt, "sc9-idem-" + poNo,
                "{\"poNo\":\"" + poNo + "\"}", "PENDING");
    }

    private void insertTicket(String id, String supplierId, String poNo, String lockAt, String unlockAt) {
        jdbc.update("INSERT INTO erp_po_coop_ticket (ID,TICKET_NO,SUPPLIER_ID,SUPPLIER_NAME,LOCK_PO_NOS,"
                + "LOCK_AT,STATUS,UNLOCK_BY,UNLOCK_AT,UNLOCK_REASON,CREATE_BY,DEL_FLAG,VER_NO) VALUES "
                + "(?,?,?,?,?,?,'DONE','sc9-emp',?,'冒烟解锁','sc9-emp','0',0)",
                id, "SC9-" + id, supplierId, "SC9 供应商", poNo, lockAt, unlockAt);
    }

    private void purge() {
        jdbc.execute("DELETE FROM erp_scm_scorecard_result WHERE SUPPLIER_ID LIKE 'SC9-%'");
        jdbc.execute("DELETE FROM erp_scm_cpfr_feedback WHERE AGREEMENT_ID LIKE 'sc9-%'");
        jdbc.execute("DELETE FROM erp_scm_cpfr_agreement WHERE SUPPLIER_ID LIKE 'SC9-%'");
        jdbc.execute("DELETE FROM erp_po_coop_ticket WHERE TICKET_NO LIKE 'SC9-%'");
        jdbc.execute("DELETE FROM erp_proc_po WHERE PO_NO LIKE 'SC9-PO-%'");
        jdbc.execute("DELETE FROM erp_proc_replenish_confirm WHERE ID LIKE 'sc9-%'");
        jdbc.execute("DELETE FROM erp_ops_outbox WHERE IDEMPOTENCY_KEY LIKE 'sc9-%'");
    }
}
