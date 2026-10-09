package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.service.bi.ExportService;
import com.erp.service.inv.InvReportService;
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
 * 双路径导出数据源（change add-inventory-reports，spec 报表双路径导出，任务 7.3）：
 * exportRows 三类型表头/行数据、maxRows 截断标记、非法类型 422、
 * 后台导出 submit(dataset=inv-report) 落任务参数。
 */
@SpringBootTest
class InvReportExportDbTest {

    private static final String WH = "WH-RPTX";
    private static final String ITEM = "IT-RPTX-01";

    @Autowired
    private InvReportService reportService;
    @Autowired
    private ExportService exportService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        cleanup();
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, SERIAL_FLAG, "
                + "STANDARD_COST, ABC_CLASS, STATUS, CREATE_BY) VALUES ('rptx-it-01', ?, "
                + "'导出测试物料', '0001', 'PCS', 'G001', 'BUY', 'NORMAL', '1', '0', 10, 'A', '1', 'junit')",
                ITEM);
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                + "BIN_CODE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, CREATE_BY) VALUES "
                + "('rptx-stk-1', ?, ?, '导出测试物料', 'RPTX-B1', 'BIN-RPTX-1', 100, 0, 0, 100, ?, 'junit')",
                WH, ITEM, LocalDate.now().minusDays(10).toString());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("rptx-user", "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM erp_inv_stock WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_bi_export_task WHERE USER_NAME = 'rptx-user'");
    }

    @SuppressWarnings("unchecked")
    @Test
    void realtimeExportRowsAndTruncate() {
        Map<String, Object> data = reportService.exportRows("realtime",
                Map.of("warehouseCode", WH, "maxRows", 10));
        List<String> headers = (List<String>) data.get("headers");
        List<List<Object>> rows = (List<List<Object>>) data.get("rows");
        assertTrue(headers.contains("库龄(天)"), "表头含库龄列：" + headers);
        assertEquals(1, rows.size());
        assertEquals("A", rows.get(0).get(10), "ABC 列");

        // maxRows=0 → 截断标记（估行分流依据）
        Map<String, Object> t = reportService.exportRows("realtime",
                Map.of("warehouseCode", WH, "maxRows", 0));
        assertTrue(Boolean.TRUE.equals(t.get("truncated")), "超限截断标记");
    }

    @SuppressWarnings("unchecked")
    @Test
    void agingAndTurnoverExportRows() {
        Map<String, Object> aging = reportService.exportRows("aging",
                Map.of("warehouseCode", WH, "maxRows", 10));
        List<List<Object>> arows = (List<List<Object>>) aging.get("rows");
        assertEquals(1, arows.size());
        assertEquals(10, ((Number) arows.get(0).get(7)).intValue(), "库龄 10 天");

        Map<String, Object> turnover = reportService.exportRows("turnover",
                Map.of("from", LocalDate.now().minusDays(30).toString(),
                        "to", LocalDate.now().toString(),
                        "scope", "SALES_OUT", "groupBy", "ITEM", "maxRows", 10));
        assertTrue(((List<?>) turnover.get("headers")).size() >= 6);
    }

    @Test
    void invalidTypeRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> reportService.exportRows("nope", Map.of()));
        assertEquals(422, ex.getCode());
    }

    @Test
    void backgroundSubmitWithInvReportDataset() {
        Map<String, Object> out = exportService.submit("inv-report",
                Map.of("type", "realtime", "warehouseCode", WH), 50000L);
        assertEquals("PENDING", out.get("approvalStatus"), ">EXPORT_ROW_LIMIT 走审批（C-4.10-05）");
        String params = jdbc.queryForObject(
                "SELECT PARAMS_JSON FROM erp_bi_export_task WHERE USER_NAME = 'rptx-user' "
                        + "ORDER BY CREATE_DATE DESC LIMIT 1", String.class);
        assertTrue(params.contains("inv-report"), params);
        assertTrue(params.contains("realtime"), params);
    }
}
