package com.erp.mo;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.service.qms.InspectionLotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 5.10.2 外协收货检验工作台库测（task 3.1，spec outsourcing-execution
 * 「外协收货检验工作台」）：外协 PO 来源批带供应商/派工单/工序/供料上下文、
 * 非外协批不出现、SCAR 联动提示。
 */
@SpringBootTest
class OutsourceInspectionDbTest {

    @Autowired
    private InspectionLotService lotService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        cleanup();
        // 外协派工单（PO 回联）
        jdbc.update("INSERT INTO erp_mrp_dispatch "
                + "(ID, DISPATCH_NO, MO_ID, MO_NO, OP_SEQ, OP_NAME, WC_CODE, WC_NAME, WC_TYPE, "
                + " DURATION_HOURS, STATUS, OUTSOURCE_FLAG, PO_NO, RECEIPT_FLAG, RECV_QTY, "
                + " OS_SUPPLY_TYPE, DEL_FLAG, VER_NO) VALUES "
                + "('osil-d1', 'DPOSI01', 'osil-mo1', 'MOOSI01', 20, '电镀', 'OSWC', '外协中心', "
                + " 'OUTSOURCED', 8, 'RELEASED', '1', 'POOSI01', '1', 10, 'COMPANY_SUPPLY', '0', 0)");
        // 外协 GR + 检验批（待检）
        jdbc.update("INSERT INTO erp_proc_gr (ID, GR_NO, PO_NO, SUPPLIER_ID, SUPPLIER_NAME, STATUS, "
                + "ARRIVAL_DATE, CREATE_DATE, DEL_FLAG, VER_NO) VALUES "
                + "('osil-gr1', 'GROSI01', 'POOSI01', 'sup-x', '外协供应商X', 'POSTED', CURDATE(), "
                + " NOW(), '0', 0)");
        jdbc.update("INSERT INTO erp_qms_inspection_lot "
                + "(ID, LOT_NO, LOT_TYPE, SOURCE_TYPE, GR_ID, GR_LINE_ID, ITEM_CODE, ITEM_NAME, "
                + " SUPPLIER_ID, SUPPLIER_NAME, BATCH_NO, LOT_QTY, STATUS, RESULT, DEL_FLAG, VER_NO) "
                + "VALUES ('osil-l1', 'ILOSI01', 'IQC', 'PURCHASE', 'osil-gr1', 'osil-gl1', "
                + " 'ITM-OS01', '外协加工件', 'sup-x', '外协供应商X', 'BOSI01', 10, 'PENDING', "
                + " 'PENDING', '0', 0)");
        // 外协批（不合格 → SCAR 提示）
        jdbc.update("INSERT INTO erp_qms_inspection_lot "
                + "(ID, LOT_NO, LOT_TYPE, SOURCE_TYPE, GR_ID, GR_LINE_ID, ITEM_CODE, ITEM_NAME, "
                + " SUPPLIER_ID, SUPPLIER_NAME, BATCH_NO, LOT_QTY, STATUS, RESULT, DEL_FLAG, VER_NO) "
                + "VALUES ('osil-l2', 'ILOSI02', 'IQC', 'PURCHASE', 'osil-gr1', 'osil-gl2', "
                + " 'ITM-OS01', '外协加工件', 'sup-x', '外协供应商X', 'BOSI02', 5, 'JUDGED', "
                + " 'FAIL', '0', 0)");
        // 非外协 GR + 批（负例：普通采购 PO）
        jdbc.update("INSERT INTO erp_proc_gr (ID, GR_NO, PO_NO, SUPPLIER_ID, SUPPLIER_NAME, STATUS, "
                + "ARRIVAL_DATE, CREATE_DATE, DEL_FLAG, VER_NO) VALUES "
                + "('osil-gr2', 'GROSI02', 'PO-NORMAL', 'sup-y', '普通供应商Y', 'POSTED', CURDATE(), "
                + " NOW(), '0', 0)");
        jdbc.update("INSERT INTO erp_qms_inspection_lot "
                + "(ID, LOT_NO, LOT_TYPE, SOURCE_TYPE, GR_ID, GR_LINE_ID, ITEM_CODE, ITEM_NAME, "
                + " SUPPLIER_ID, SUPPLIER_NAME, BATCH_NO, LOT_QTY, STATUS, RESULT, DEL_FLAG, VER_NO) "
                + "VALUES ('osil-l3', 'ILOSI03', 'IQC', 'PURCHASE', 'osil-gr2', 'osil-gl3', "
                + " 'ITM-N01', '普通采购件', 'sup-y', '普通供应商Y', 'BN01', 3, 'PENDING', "
                + " 'PENDING', '0', 0)");
        MoTestAuth.login("osi-qc", "ROLE_INSPECTOR");
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM erp_qms_inspection_lot WHERE LOT_NO LIKE 'ILOSI%'");
        jdbc.update("DELETE FROM erp_proc_gr WHERE GR_NO LIKE 'GROSI%'");
        jdbc.update("DELETE FROM erp_mrp_dispatch WHERE DISPATCH_NO LIKE 'DPOSI%'");
    }

    @Test
    void outsourceBoardShowsContextAndExcludesNormal() {
        Page<Map<String, Object>> page = lotService.outsourceBoard(1, 50, null);
        List<Map<String, Object>> rows = page.getRecords();
        assertEquals(2, rows.size(), "仅外协 PO 来源批（非外协 ILOSI03 不出现）");
        Map<String, Object> l1 = rows.stream()
                .filter(r -> "ILOSI01".equals(r.get("lotNo"))).findFirst().orElseThrow();
        assertEquals("DPOSI01", l1.get("dispatchNo"), "派工单号带出");
        assertEquals("MOOSI01", l1.get("moNo"));
        assertEquals(20, (int) (Integer) l1.get("opSeq"));
        assertEquals("COMPANY_SUPPLY", l1.get("osSupplyType"), "供料方式上下文");
        assertEquals("POOSI01", l1.get("poNo"));
        assertNotNull(l1.get("supplierName"));
        assertEquals(Boolean.FALSE, l1.get("scarHint"), "待检批无 SCAR 提示");
    }

    @Test
    void failedLotCarriesScarHint() {
        Page<Map<String, Object>> page = lotService.outsourceBoard(1, 50, null);
        Map<String, Object> l2 = page.getRecords().stream()
                .filter(r -> "ILOSI02".equals(r.get("lotNo"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, l2.get("scarHint"), "不合格批联动 SCAR 提示");
    }

    @Test
    void statusFilterWorks() {
        Page<Map<String, Object>> page = lotService.outsourceBoard(1, 50, "PENDING");
        assertEquals(1, page.getRecords().size(), "状态筛选 PENDING");
        assertTrue(page.getRecords().stream().allMatch(r -> "PENDING".equals(r.get("status"))));
    }
}
