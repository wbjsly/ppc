package com.erp.inv;

import com.erp.service.inv.ExpiryReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每日效期预警报告 DB 侧（spec expiry-management 需求①，任务 3.1/3.3）：
 * 首日无基线、二次生成覆盖不重复、三级计数正确、diff 新增/解除、详情可查。
 */
@SpringBootTest
class ExpiryReportDbTest {

    private static final String ITEM = "IT-EXPIRY-REPORT-TEST";

    @Autowired
    private ExpiryReportService service;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM erp_inv_expiry_lock_log WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_expiry_report WHERE 1=1");   // 报告为全局单行/日，清干净避免历史干扰
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE = 'EXPIRY_REPORT'");
        // warnings() 仅返回 batchFlag=1 物料
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, SERIAL_FLAG, "
                + "STATUS, CREATE_BY) VALUES ('junit-rpt-it', ?, '报告测试物料', '0001', 'PCS', "
                + "'G001', 'BUY', 'NORMAL', '1', '0', '1', 'junit')", ITEM);
        login("wh-rpt", "ROLE_WAREHOUSE");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM erp_inv_expiry_lock_log WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_expiry_report WHERE 1=1");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE = 'EXPIRY_REPORT'");
        SecurityContextHolder.clearContext();
    }

    private void login(String user, String... roles) {
        var authorities = java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "n/a", authorities));
    }

    private void insertBatch(String batch, LocalDate production, LocalDate expiry) {
        jdbc.update("INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, "
                        + "PRODUCTION_DATE, EXPIRY_DATE, EXPIRY_LOCK_FLAG, LOCK_SOURCE, "
                        + "STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                        + "VALUES (?, ?, ?, '报告测试物料', ?, ?, '0', 'AUTO', '1', 'junit', '0', 0)",
                "junit-rpt-" + batch, batch, ITEM,
                production.toString(), expiry.toString());
    }

    /** 需求①：首日无基线 + 三级计数 */
    @Test
    void firstGenerationHasNoBaseline() {
        insertBatch("B-Y", LocalDate.now().minusDays(100), LocalDate.now().plusDays(80));  // 剩 80 黄
        insertBatch("B-O", LocalDate.now().minusDays(80), LocalDate.now().plusDays(50));   // 剩 50 橙
        insertBatch("B-R", LocalDate.now().minusDays(90), LocalDate.now().plusDays(20));   // 剩 20 红

        Map<String, Object> out = service.generateDailyReport();

        assertEquals(1, out.get("yellowCnt"));
        assertEquals(1, out.get("orangeCnt"));
        assertEquals(1, out.get("redCnt"));
        assertEquals(Boolean.TRUE, out.get("noBaseline"), "首日无前报基线");

        // 今日唯一（重复生成覆盖）
        Integer todayRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_inv_expiry_report WHERE REPORT_DATE = ?",
                Integer.class, LocalDate.now().toString());
        assertEquals(1, todayRows);
        Map<String, Object> again = service.generateDailyReport();
        assertEquals(1, again.get("yellowCnt"), "二次生成幂等");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_inv_expiry_report WHERE REPORT_DATE = ?",
                Integer.class, LocalDate.now().toString()), "覆盖不产生重复行");
    }

    /** diff：批次效期迁出预警线 → 解除清单；新批次进入 → 新增清单 */
    @Test
    void diffTracksAddedAndCleared() {
        // 第一日：B-A 在预警线内
        insertBatch("B-A", LocalDate.now().minusDays(90), LocalDate.now().plusDays(25));  // 红
        service.generateDailyReport();

        // 模拟"前报"为昨日（当前行是今日）：把今日报告日期改成昨日，再生成今日
        jdbc.update("UPDATE erp_inv_expiry_report SET REPORT_DATE = ? WHERE REPORT_DATE = ?",
                LocalDate.now().minusDays(1).toString(), LocalDate.now().toString());
        // B-A 效期拉远（出线）+ 新增 B-B 进线
        jdbc.update("UPDATE erp_inv_batch SET EXPIRY_DATE = ? WHERE ITEM_CODE = ? AND BATCH_NO = ?",
                LocalDate.now().plusDays(500).toString(), ITEM, "B-A");
        insertBatch("B-B", LocalDate.now().minusDays(80), LocalDate.now().plusDays(40));  // 橙

        Map<String, Object> out = service.generateDailyReport();

        assertEquals(Boolean.FALSE, out.get("noBaseline"), "有前报则有基线");
        Map<String, Object> detail = service.detail(LocalDate.now().toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> added = (List<Map<String, Object>>) detail.get("added");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cleared = (List<Map<String, Object>>) detail.get("cleared");
        assertEquals(1, added.size(), "新增 B-B");
        assertEquals("B-B", added.get(0).get("batchNo"));
        assertEquals(1, cleared.size(), "解除 B-A");
        assertEquals("B-A", cleared.get(0).get("batchNo"));
    }

    /** 历史与详情接口 */
    @Test
    void historyAndDetailQueryable() {
        insertBatch("B-H", LocalDate.now().minusDays(95), LocalDate.now().plusDays(30));
        service.generateDailyReport();

        List<Map<String, Object>> history = service.history(10);
        assertEquals(1, history.size());
        assertEquals(LocalDate.now().toString(), String.valueOf(history.get(0).get("reportDate")));

        Map<String, Object> d = service.detail(LocalDate.now().toString());
        assertEquals(1, d.get("redCnt"));
        assertTrue(((List<?>) d.get("detail")).size() >= 1, "明细可查");

        // 格式错误 422
        assertThrows(com.erp.common.ServiceException.class, () -> service.detail("bad-date"));
    }
}
