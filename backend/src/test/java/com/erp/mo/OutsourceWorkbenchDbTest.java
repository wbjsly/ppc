package com.erp.mo;

import com.erp.common.ServiceException;
import com.erp.entity.mrp.MrpDispatch;
import com.erp.service.mrp.DispatchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 5.10.1 外协订单工作台库测（task 2.1，spec outsourcing-execution「外协订单工作台」）：
 * 聚合按回联状态分组（PENDING_PR/ORDERED/RECEIVED）、状态/供应商/WC 筛选与关键词、
 * 外协字段维护留痕、非外协 422、供料方式枚举校验、越权 403。
 */
@SpringBootTest
class OutsourceWorkbenchDbTest {

    @Autowired
    private DispatchService dispatchService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM erp_mrp_dispatch WHERE DISPATCH_NO LIKE 'DPWB%'");
        jdbc.update("DELETE FROM erp_mrp_work_center WHERE WC_CODE LIKE 'WBWC-%'");
        // 合格供应商 SUPFINTGT（sup-fin-tgt，库里既有）挂外协 WC
        jdbc.update("INSERT INTO erp_mrp_work_center "
                + "(ID, WC_CODE, WC_NAME, WC_TYPE, SUPPLIER_CODE, STATUS, DEL_FLAG, VER_NO) VALUES "
                + "('wbwc-os', 'WBWC-OS', '外协台中心', 'OUTSOURCED', 'SUPFINTGT', '1', '0', 0)");
        // 非外协派工单（负例用）
        insertDispatch("DPWB-N01", "0", null, null, "INTERNAL");
        // 三张外协派工单覆盖三回联状态
        insertDispatch("DPWB-P01", "1", null, null, "OUTSOURCED");                    // PENDING_PR
        insertDispatch("DPWB-O01", "1", "PRWB001", "POWB001", "OUTSOURCED");          // ORDERED
        insertDispatch("DPWB-R01", "1", "PRWB002", "POWB002", "OUTSOURCED");          // RECEIVED
        jdbc.update("UPDATE erp_mrp_dispatch SET RECEIPT_FLAG='1', RECV_QTY=10 "
                + "WHERE DISPATCH_NO='DPWB-R01'");
        MoTestAuth.login("wb-planner", "ROLE_PLANNER");
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        jdbc.update("DELETE FROM erp_mrp_dispatch WHERE DISPATCH_NO LIKE 'DPWB%'");
        jdbc.update("DELETE FROM erp_mrp_work_center WHERE WC_CODE LIKE 'WBWC-%'");
    }

    private void insertDispatch(String no, String osFlag, String prNo, String poNo, String wcType) {
        jdbc.update("INSERT INTO erp_mrp_dispatch "
                + "(ID, DISPATCH_NO, MO_ID, MO_NO, OP_SEQ, OP_NAME, WC_CODE, WC_NAME, WC_TYPE, "
                + " DURATION_HOURS, STATUS, OUTSOURCE_FLAG, PR_NO, PO_NO, RECEIPT_FLAG, RECV_QTY, "
                + " OS_SUPPLY_TYPE, DEL_FLAG, VER_NO) VALUES "
                + "(?, ?, ?, ?, 10, '机加工', ?, ?, ?, 8, 'RELEASED', ?, ?, ?, '0', 0, "
                + "'SUPPLIER_BUY', '0', 0)",
                "wb-" + no.toLowerCase(), no, "wb-mo-" + no.toLowerCase(), "MO" + no,
                "WBWC-OS".equals(wcType) || "OUTSOURCED".equals(wcType) ? "WBWC-OS" : "WBWC-IN",
                "外协中心", wcType, osFlag, prNo, poNo);
    }

    // ---------- 聚合分组 ----------

    @Test
    void workbenchGroupsByLinkStatus() {
        List<Map<String, Object>> rows = dispatchService.workbench(null, null, null, null);
        assertEquals(3, rows.size(), "仅外协派工单进工作台（非外协 DPWB-N01 排除）");
        Map<String, Object> p = row(rows, "DPWB-P01");
        Map<String, Object> o = row(rows, "DPWB-O01");
        Map<String, Object> r = row(rows, "DPWB-R01");
        assertEquals("PENDING_PR", p.get("linkStatus"));
        assertEquals("ORDERED", o.get("linkStatus"));
        assertEquals("RECEIVED", r.get("linkStatus"));
        assertEquals("POWB001", o.get("poNo"));
        assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) r.get("recvQty")),
                "收货累计带出");
        // 供应商随 WC 带出
        assertEquals("SUPFINTGT", p.get("supplierCode"));
        assertNotNull(p.get("supplierName"), "供应商名称解析");
    }

    @Test
    void workbenchFiltersAndKeyword() {
        assertEquals(1, dispatchService.workbench("ORDERED", null, null, null).size(), "状态筛选");
        assertEquals(3, dispatchService.workbench(null, "SUPFINTGT", null, null).size(), "供应商筛选");
        assertEquals(0, dispatchService.workbench(null, "SUP-NONE", null, null).size(), "无匹配供应商空集");
        assertEquals(1, dispatchService.workbench(null, null, null, "POWB002").size(), "关键词搜 PO 号");
        assertEquals(0, dispatchService.workbench("PENDING_PR", null, null, "POWB001").size(),
                "状态与关键词组合");
    }

    // ---------- 字段维护 ----------

    @Test
    void maintainOsFieldsWritesAndAudits() {
        MrpDispatch d = first("DPWB-P01");
        MrpDispatch updated = dispatchService.maintainOsFields(
                d.getId(), "2026-11-30", "来料须带材质证明", "COMPANY_SUPPLY");
        assertEquals(LocalDate.of(2026, 11, 30), updated.getOsDueDate(), "外协交期保存");
        assertEquals("来料须带材质证明", updated.getOsRemark());
        assertEquals("COMPANY_SUPPLY", updated.getOsSupplyType(), "供料方式切甲供");
        assertNotNull(updated.getUpdateBy(), "留痕操作人");
        // 落库复验
        Map<String, Object> db = jdbc.queryForMap(
                "SELECT OS_DUE_DATE, OS_SUPPLY_TYPE FROM erp_mrp_dispatch WHERE DISPATCH_NO='DPWB-P01'");
        assertEquals("2026-11-30", String.valueOf(db.get("OS_DUE_DATE")));
        assertEquals("COMPANY_SUPPLY", db.get("OS_SUPPLY_TYPE"));
    }

    @Test
    void maintainRejectsNonOutsourceAndBadEnum() {
        MrpDispatch n = first("DPWB-N01");
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> dispatchService.maintainOsFields(n.getId(), null, "x", null));
        assertEquals(422, e1.getCode(), "非外协派工单 422");
        MrpDispatch d = first("DPWB-P01");
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> dispatchService.maintainOsFields(d.getId(), null, null, "XXX"));
        assertEquals(422, e2.getCode(), "供料方式枚举 422");
    }

    @Test
    void workbenchForbiddenForInspector() {
        MoTestAuth.logout();
        MoTestAuth.login("wb-insp", "ROLE_INSPECTOR");
        ServiceException e = assertThrows(ServiceException.class,
                () -> dispatchService.workbench(null, null, null, null));
        assertEquals(403, e.getCode(), "质检员不属计划采购权限线");
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> dispatchService.maintainOsFields("x", null, null, null));
        assertEquals(403, e2.getCode(), "质检员不可维护外协字段");
    }

    // ---------- helpers ----------

    private MrpDispatch first(String no) {
        return jdbc.queryForObject("SELECT ID FROM erp_mrp_dispatch WHERE DISPATCH_NO=?",
                (rs, i) -> {
                    MrpDispatch d = new MrpDispatch();
                    d.setId(rs.getString(1));
                    return d;
                }, no);
    }

    private Map<String, Object> row(List<Map<String, Object>> rows, String no) {
        return rows.stream().filter(r -> no.equals(r.get("dispatchNo")))
                .findFirst().orElseThrow(() -> new AssertionError("缺行 " + no));
    }
}
