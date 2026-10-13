package com.erp.proc;

import com.erp.common.ServiceException;
import com.erp.entity.proc.OsAp;
import com.erp.entity.proc.OsSettlement;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.proc.OsSettlementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外协对账与应付挂账 DbTest（change add-outsourcing-processing，spec outsourcing-settlement，
 * task 6.1/6.2）：按 PO 汇总生成（金额=数量×PO 单价）、重复引用排除、无明细 422、
 * 审批通过生成 OPEN 挂账、驳回回 DRAFT 释放引用、POSTED 不可改、越权 403。
 */
@SpringBootTest
class OsSettlementDbTest {

    @Autowired
    private OsSettlementService service;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String PO = "POSRT-T1";
    private static final String PO2 = "POSRT-T2";
    private static final String DP = "DPSRT-T1";
    private static final String ITEM = "ITOSRT-T1";
    private static final String SUP = "SUPOSRT-T1";

    @BeforeEach
    void seed() {
        cleanup();
        TestAuth.login("purchaser-t", "ROLE_PURCHASER");
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, SERIAL_FLAG, "
                + "STATUS, CREATE_BY, DEL_FLAG) VALUES (?, ?, '对账测试物料', '0001', 'KG', "
                + "'G001', 'BUY', 'NORMAL', '1', '0', '1', 'junit', '0')", ITEM, ITEM);
        jdbc.update("INSERT INTO erp_mdm_supplier (ID, SUPPLIER_CODE, SUPPLIER_NAME, STATUS, "
                + "CREATE_BY, DEL_FLAG) VALUES (?, ?, '对账测试供应商', 'ACTIVE', 'junit', '0')",
                SUP, SUP);
        // 外协工作中心 + 外协派工单（OUTSOURCE_FLAG=1 + PO 回联）
        jdbc.update("INSERT INTO erp_mrp_work_center (ID, WC_CODE, WC_NAME, WC_TYPE, SUPPLIER_CODE, "
                + "STATUS, CREATE_BY, DEL_FLAG) VALUES ('wcsrt-t', 'WCSRT-T', '对账测试外协中心', "
                + "'OUTSOURCED', ?, '1', 'junit', '0')", SUP);
        jdbc.update("INSERT INTO erp_mrp_dispatch (ID, DISPATCH_NO, MO_ID, MO_NO, OP_SEQ, OP_NAME, "
                + "WC_CODE, WC_NAME, OUTSOURCE_FLAG, PO_NO, STATUS, OS_SUPPLY_TYPE, CREATE_BY, "
                + "DEL_FLAG) VALUES (?, ?, 'mo-srt-t1', 'MOSRT-T1', 20, '外协工序', 'WCSRT-T', "
                + "'对账测试外协中心', '1', ?, 'CONFIRMED', 'SUPPLIER_BUY', 'junit', '0')",
                DP, DP, PO);
        // 收货单 + 过账行（两条，数量 10/20，单价 5.5）
        mkPostedGr("GRT-T1", PO, "10", "5.5");
        mkPostedGr("GRT-T2", PO, "20", "5.5");
        // 第二个 PO（无收货 → 无明细 422 用例）
        jdbc.update("INSERT INTO erp_mrp_dispatch (ID, DISPATCH_NO, MO_ID, MO_NO, OP_SEQ, OP_NAME, "
                + "WC_CODE, WC_NAME, OUTSOURCE_FLAG, PO_NO, STATUS, OS_SUPPLY_TYPE, CREATE_BY, "
                + "DEL_FLAG) VALUES ('dpsrt-t2', 'DPSRT-T2', 'mo-srt-t2', 'MOSRT-T2', 20, '外协工序', "
                + "'WCSRT-T', '对账测试外协中心', '1', ?, 'CONFIRMED', 'SUPPLIER_BUY', 'junit', '0')",
                PO2);
    }

    @AfterEach
    void tearDown() {
        TestAuth.logout();
        cleanup();
    }

    /** 造一张已过账收货单 + 一条过账行（数量/单价指定） */
    private void mkPostedGr(String grNo, String poNo, String qty, String price) {
        String grId = "gr-" + grNo.toLowerCase();
        jdbc.update("INSERT INTO erp_proc_gr (ID, GR_NO, PO_NO, SUPPLIER_ID, SUPPLIER_NAME, STATUS, "
                + "ARRIVAL_DATE, CREATE_BY, DEL_FLAG) VALUES (?, ?, ?, ?, '对账测试供应商', 'POSTED', "
                + "CURDATE(), 'junit', '0')", grId, grNo, poNo, SUP);
        jdbc.update("INSERT INTO erp_proc_gr_line (ID, GR_ID, LINE_NO, ITEM_CODE, ITEM_NAME, UNIT, "
                + "ORDERED_QTY, OPEN_QTY, RECEIVED_QTY, WITHIN_TOLERANCE_QTY, FROZEN_QTY, STATUS, "
                + "QC_STATUS, UNIT_PRICE, AMOUNT, CREATE_BY) VALUES "
                + "(?, ?, 1, ?, '对账测试物料', 'PCS', ?, 0, ?, ?, 0, 'POSTED', 'RELEASED', ?, ?, "
                + "'junit')",
                "gl-" + grNo.toLowerCase(), grId, ITEM, qty, qty, qty, price,
                new BigDecimal(qty).multiply(new BigDecimal(price)));
    }

    private void cleanup() {
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID "
                + "WHERE a.BIZ_TYPE = 'OutsourceSettlement'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE = 'OutsourceSettlement'");
        jdbc.update("DELETE FROM erp_proc_os_ap WHERE OSR_NO LIKE 'OSRTST%' OR SUPPLIER_CODE = ?", SUP);
        jdbc.update("DELETE l FROM erp_proc_os_settlement_line l JOIN erp_proc_os_settlement h "
                + "ON h.ID = l.OSR_ID WHERE h.PO_NO IN (?, ?)", PO, PO2);
        jdbc.update("DELETE FROM erp_proc_os_settlement WHERE PO_NO IN (?, ?)", PO, PO2);
        jdbc.update("DELETE FROM erp_proc_gr_line WHERE GR_ID LIKE 'gr-grt-%' OR GR_ID = 'gr-nos'");
        jdbc.update("DELETE FROM erp_proc_gr WHERE GR_NO LIKE 'GRT-T%' OR GR_NO = 'GRNOS-T'");
        jdbc.update("DELETE FROM erp_mrp_dispatch WHERE DISPATCH_NO LIKE 'DPSRT-T%'");
        jdbc.update("DELETE FROM erp_mrp_work_center WHERE WC_CODE = 'WCSRT-T'");
        jdbc.update("DELETE FROM erp_mdm_supplier WHERE SUPPLIER_CODE = ?", SUP);
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE = ?", ITEM);
    }

    // ---------- 6.1 生成 ----------

    @Test
    void generateAggregatesPostedLinesWithPoPrice() {
        OsSettlement h = service.generate(PO);
        assertNotNull(h.getOsrNo(), "单号生成");
        assertTrue(h.getOsrNo().startsWith("OSR"), "OSR 前缀");
        assertEquals(OsSettlement.ST_DRAFT, h.getStatus(), "初始草稿");
        // 数量 10+20=30，金额 30×5.5=165
        assertEquals(0, new BigDecimal("30").compareTo(h.getTotalQty()), "对账数量合计");
        assertEquals(0, new BigDecimal("165.00").compareTo(h.getTotalAmt()), "金额=数量×PO 单价");
        Map<String, Object> d = service.detail(h.getId());
        assertEquals(2, ((List<?>) d.get("lines")).size(), "两条收货明细");
    }

    @Test
    void postedSettlementLocksReceiptLines() {
        // 已挂账对账单占住收货行引用 → 再次汇总这些行被排除 → 无剩余明细 422
        OsSettlement a = service.generate(PO);
        service.submit(a.getId());
        TestAuth.login("mgr-t", "ROLE_PLAN_MGR");
        approvalEngine.pass(activeTaskId(a.getId()), "同意");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.generate(PO));
        assertEquals(422, ex.getCode(), "已对账行被排除 → 无未对账明细 422");
    }

    @Test
    void draftDeleteReleasesReceiptLineReference() {
        // 草稿占住引用 → 同 PO 拒绝重复建单
        OsSettlement a = service.generate(PO);
        ServiceException dup = assertThrows(ServiceException.class, () -> service.generate(PO));
        assertEquals(422, dup.getCode(), "同 PO 已有草稿 → 拒绝重复建单");

        // 删除草稿物理释放唯一键 → 重新汇总完整
        service.delete(a.getId());
        OsSettlement b = service.generate(PO);
        assertEquals(0, new BigDecimal("30").compareTo(b.getTotalQty()), "引用释放后可重新汇总");
        service.delete(b.getId());
    }

    @Test
    void generateNonOutsourcePoBlocked() {
        // 无外协派工单回联的 PO
        jdbc.update("INSERT INTO erp_proc_gr (ID, GR_NO, PO_NO, SUPPLIER_ID, SUPPLIER_NAME, STATUS, "
                + "ARRIVAL_DATE, CREATE_BY, DEL_FLAG) VALUES ('gr-nos', 'GRNOS-T', 'PO-NOS', ?, "
                + "'对账测试供应商', 'POSTED', CURDATE(), 'junit', '0')", SUP);
        jdbc.update("INSERT INTO erp_proc_gr_line (ID, GR_ID, LINE_NO, ITEM_CODE, ITEM_NAME, UNIT, "
                + "RECEIVED_QTY, WITHIN_TOLERANCE_QTY, STATUS, QC_STATUS, UNIT_PRICE, CREATE_BY) "
                + "VALUES ('gl-nos', 'gr-nos', 1, ?, '对账测试物料', 'PCS', 5, 5, 'POSTED', "
                + "'RELEASED', 5, 'junit')", ITEM);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.generate("PO-NOS"));
        assertEquals(422, ex.getCode(), "非外协 PO 422");
    }

    @Test
    void generateNoUnsettledDetailBlocked() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.generate(PO2));
        assertEquals(422, ex.getCode(), "无未对账收货 422");
    }

    // ---------- 6.2 审批与挂账 ----------

    @Test
    void approveGeneratesOpenAp() {
        OsSettlement h = service.generate(PO);
        service.submit(h.getId());
        assertEquals(OsSettlement.ST_CONFIRMED, headOf(h.getId()).getStatus(), "提交后审批中");
        String taskId = activeTaskId(h.getId());
        TestAuth.login("mgr-t", "ROLE_PLAN_MGR");
        approvalEngine.pass(taskId, "金额无误");

        OsSettlement posted = headOf(h.getId());
        assertEquals(OsSettlement.ST_POSTED, posted.getStatus(), "通过 → POSTED");
        assertNotNull(posted.getPostDate(), "挂账时间留痕");
        // 挂账记录：OPEN + 金额一致
        Map<String, Object> ap = jdbc.queryForMap(
                "SELECT AP_NO, AMT, STATUS, OSR_NO FROM erp_proc_os_ap WHERE OSR_NO = ?",
                posted.getOsrNo());
        assertEquals("OPEN", ap.get("STATUS"), "挂账 OPEN");
        assertEquals(0, new BigDecimal("165.00").compareTo((BigDecimal) ap.get("AMT")),
                "挂账金额 = 对账单金额");
        List<OsAp> aps = service.listAp(SUP, "OPEN");
        assertEquals(1, aps.size(), "挂账列表可查");
    }

    @Test
    void rejectBackToDraftReleasesForResubmit() {
        OsSettlement h = service.generate(PO);
        service.submit(h.getId());
        String taskId = activeTaskId(h.getId());
        TestAuth.login("mgr-t", "ROLE_PLAN_MGR");
        approvalEngine.reject(taskId, "单价需复核");

        OsSettlement back = headOf(h.getId());
        assertEquals(OsSettlement.ST_DRAFT, back.getStatus(), "驳回回 DRAFT 可改");
        assertEquals("单价需复核", back.getRejectReason(), "驳回意见留痕");
        // 引用释放回可编辑 → 可重新提交
        service.submit(h.getId());
        assertEquals(OsSettlement.ST_CONFIRMED, headOf(h.getId()).getStatus(), "可重新提交");
        // 无挂账生成
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_proc_os_ap WHERE OSR_NO = ?", Integer.class, back.getOsrNo()),
                "驳回不生成挂账");
    }

    @Test
    void postedSettlementImmutable() {
        OsSettlement h = service.generate(PO);
        service.submit(h.getId());
        TestAuth.login("mgr-t", "ROLE_PLAN_MGR");
        approvalEngine.pass(activeTaskId(h.getId()), "同意");

        ServiceException ex1 = assertThrows(ServiceException.class, () -> service.delete(h.getId()));
        assertEquals(422, ex1.getCode(), "已挂账不可删除");
        ServiceException ex2 = assertThrows(ServiceException.class, () -> service.submit(h.getId()));
        assertEquals(422, ex2.getCode(), "已挂账不可再提交");
    }

    @Test
    void qualityRoleForbidden() {
        OsSettlement h = service.generate(PO);
        TestAuth.login("qc-t", "ROLE_INSPECTOR");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.generate(PO2));
        assertEquals(403, ex.getCode(), "质检员无对账职权 403");
        TestAuth.login("qc-t", "ROLE_INSPECTOR");
        ServiceException ex2 = assertThrows(ServiceException.class, () -> service.delete(h.getId()));
        assertEquals(403, ex2.getCode(), "删除同样 403");
    }

    // ---------- helpers ----------

    @SuppressWarnings("unchecked")
    private OsSettlement headOf(String id) {
        return (OsSettlement) service.detail(id).get("osr");
    }

    private String activeTaskId(String osrId) {
        return jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID "
                        + "WHERE a.BIZ_TYPE='OutsourceSettlement' AND a.BIZ_ID=? AND t.STATUS='ACTIVE'",
                String.class, osrId);
    }

    /** 进程内登录态辅助（BomTestAuth 同范式） */
    static final class TestAuth {
        private TestAuth() {
        }

        static void login(String user, String... roles) {
            List<org.springframework.security.core.authority.SimpleGrantedAuthority> auths =
                    java.util.Arrays.stream(roles)
                            .map(org.springframework.security.core.authority
                                    .SimpleGrantedAuthority::new)
                            .toList();
            org.springframework.security.core.context.SecurityContextHolder.getContext()
                    .setAuthentication(new org.springframework.security.authentication
                            .UsernamePasswordAuthenticationToken(user, "n/a", auths));
        }

        static void logout() {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }
}
