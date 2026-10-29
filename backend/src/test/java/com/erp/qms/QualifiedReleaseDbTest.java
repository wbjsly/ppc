package com.erp.qms;

import com.erp.common.ServiceException;
import com.erp.service.qms.InspectionLotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合格放行通知（change rework-qualified-release，spec 合格放行通知）：
 * IQC→WAREHOUSE、OQC→WAREHOUSE+SALES、IPQC 不发、校验失败无通知且状态不变。
 * 附 board releaseMode 过滤（task 3.1 验证）。
 */
@SpringBootTest
class QualifiedReleaseDbTest {

    @Autowired
    private InspectionLotService lotService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        purge();
        asRole("ROLE_INSPECTOR");
        seedLot("lot-qr-iqc", "IL-QR-001", "IQC");
        seedLot("lot-qr-oqc", "IL-QR-002", "OQC");
        seedLot("lot-qr-ipqc", "IL-QR-003", "IPQC");
        seedLot("lot-qr-fail", "IL-QR-004", "IQC");
    }

    @AfterEach
    void cleanup() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE='QMS_RELEASE' AND BIZ_ID LIKE 'lot-qr-%'");
        jdbc.update("DELETE FROM erp_qms_lot_item WHERE LOT_ID LIKE 'lot-qr-%'");
        jdbc.update("DELETE FROM erp_qms_inspection_lot WHERE ID LIKE 'lot-qr-%'");
    }

    private void asRole(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, role));
    }

    /** 造「判定合格待确认」批（标准绑定 + 非 CTQ 项已判合格） */
    private void seedLot(String id, String lotNo, String lotType) {
        jdbc.update("INSERT INTO erp_qms_inspection_lot (ID, LOT_NO, LOT_TYPE, SOURCE_TYPE, ITEM_CODE, ITEM_NAME, "
                + "LOT_QTY, RISK_GRADE, STATUS, RESULT, PRIORITY, STRICTNESS, STANDARD_ID, STANDARD_CODE, "
                + "STANDARD_VERSION, CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, ?, 'MANUAL', 'QR-ITEM', "
                + "'放行测试物料', 10, 'C', 'INPUTTING', 'PASS', 'NORMAL', 'NORMAL', 'std-qr-x', 'QR-STD', 1, "
                + "'junit', '0', 0)", id, lotNo, lotType);
        jdbc.update("INSERT INTO erp_qms_lot_item (ID, LOT_ID, SEQUENCE_NO, CHARACTERISTIC_NAME, CTQ_FLAG, "
                + "SPEC_TYPE, JUDGE, INSPECT_BY, INSPECT_TIME, CREATE_BY, VER_NO) VALUES "
                + "(?, ?, 1, '外观', '0', 'LOOK', 'PASS', 'tester', NOW(), 'junit', 0)", "li-" + id, id);
    }

    private List<Map<String, Object>> notices(String lotId) {
        return jdbc.queryForList(
                "SELECT TARGET_ROLE, TITLE, CONTENT FROM erp_sys_notice "
                        + "WHERE BIZ_TYPE='QMS_RELEASE' AND BIZ_ID=? AND DEL_FLAG='0'", lotId);
    }

    // ---------- IQC 放行 → 仓库上架通知 ----------

    @Test
    void iqcReleaseNotifiesWarehouse() {
        lotService.confirmRelease("lot-qr-iqc");
        List<Map<String, Object>> ns = notices("lot-qr-iqc");
        assertEquals(1, ns.size(), "IQC 仅 1 条仓库通知");
        assertEquals("ROLE_WAREHOUSE", ns.get(0).get("TARGET_ROLE"));
        String content = String.valueOf(ns.get(0).get("CONTENT"));
        assertTrue(content.contains("IL-QR-001") && content.contains("QR-ITEM"),
                "通知含批号与物料：" + content);
        assertTrue(String.valueOf(ns.get(0).get("TITLE")).contains("上架"), "标题为上架待办");
    }

    // ---------- OQC 放行 → 仓库 + 销售跟单 ----------

    @Test
    void oqcReleaseNotifiesWarehouseAndSales() {
        lotService.confirmRelease("lot-qr-oqc");
        List<Map<String, Object>> ns = notices("lot-qr-oqc");
        assertEquals(2, ns.size(), "OQC 两条通知");
        List<String> roles = ns.stream().map(m -> String.valueOf(m.get("TARGET_ROLE"))).toList();
        assertTrue(roles.contains("ROLE_WAREHOUSE") && roles.contains("ROLE_SALES"),
                "接收方为仓库+跟单：" + roles);
        long salesWithLot = ns.stream()
                .filter(m -> "ROLE_SALES".equals(m.get("TARGET_ROLE")))
                .filter(m -> String.valueOf(m.get("CONTENT")).contains("IL-QR-002"))
                .count();
        assertEquals(1, salesWithLot, "跟单通知含批号");
    }

    // ---------- IPQC 放行 → 不发通知（偏差 D-3） ----------

    @Test
    void ipqcReleaseSendsNoNotice() {
        lotService.confirmRelease("lot-qr-ipqc");
        assertEquals("RELEASED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_qms_inspection_lot WHERE ID='lot-qr-ipqc'", String.class));
        assertTrue(notices("lot-qr-ipqc").isEmpty(), "IPQC 放行不产生通知");
    }

    // ---------- 校验失败 → 无通知且状态不变 ----------

    @Test
    void validationFailureNoNotice() {
        // lot-qr-fail 缺标准绑定（standardId 置空）
        jdbc.update("UPDATE erp_qms_inspection_lot SET STANDARD_ID=NULL WHERE ID='lot-qr-fail'");
        ServiceException e = assertThrows(ServiceException.class,
                () -> lotService.confirmRelease("lot-qr-fail"));
        assertEquals(422, e.getCode());
        assertEquals("INPUTTING", jdbc.queryForObject(
                "SELECT STATUS FROM erp_qms_inspection_lot WHERE ID='lot-qr-fail'", String.class),
                "校验失败状态不变");
        assertTrue(notices("lot-qr-fail").isEmpty(), "校验失败不发通知");
    }

    // ---------- board releaseMode（task 3.1） ----------

    @Test
    void boardReleaseModeFiltersPassInputting() {
        // 待放行：lot-qr-iqc/oqc/ipqc（PASS+INPUTTING）；再造一个 RELEASED 与一个 FAIL
        seedLot("lot-qr-done", "IL-QR-005", "IQC");
        jdbc.update("UPDATE erp_qms_inspection_lot SET STATUS='RELEASED' WHERE ID='lot-qr-done'");
        seedLot("lot-qr-fail2", "IL-QR-006", "IQC");
        jdbc.update("UPDATE erp_qms_inspection_lot SET RESULT='FAIL' WHERE ID='lot-qr-fail2'");

        var page = lotService.board(1, 100, null, null, true);
        List<String> ids = page.getRecords().stream()
                .map(m -> String.valueOf(m.get("id"))).toList();
        assertTrue(ids.contains("lot-qr-iqc"), "待放行批在列");
        assertTrue(!ids.contains("lot-qr-done"), "RELEASED 不在待放行列");
        assertTrue(!ids.contains("lot-qr-fail2"), "FAIL 不在待放行列");
        for (Map<String, Object> m : page.getRecords()) {
            if (String.valueOf(m.get("id")).startsWith("lot-qr-")) {
                assertEquals("PASS", m.get("result"));
                assertEquals("INPUTTING", m.get("status"));
            }
        }

        // 不传 releaseMode = 现状（在途四状态含 RELEASED 不出现，但 FAIL 不出现、INPUTTING 出现）
        // 共享库隔离：IQC/OQC 历史批可超五百条（实测 535+），首页窗口须覆盖总量（先例同 AssetReportDbTest）
        var normal = lotService.board(1, 2000, null, null, null);
        List<String> normalIds = normal.getRecords().stream()
                .map(m -> String.valueOf(m.get("id"))).toList();
        assertTrue(normalIds.contains("lot-qr-iqc"), "不传参数行为不变");

        // 已放行历史：status=RELEASED 过滤（既有 status 参数）
        var history = lotService.board(1, 100, "RELEASED", null, null);
        assertTrue(history.getRecords().stream()
                .anyMatch(m -> "lot-qr-done".equals(m.get("id"))), "RELEASED 历史可查");
    }
}
