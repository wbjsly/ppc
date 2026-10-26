package com.erp.qms;

import com.erp.common.ServiceException;
import com.erp.entity.qms.CustomerAgreement;
import com.erp.service.qms.CustomerAgreementService;
import com.erp.service.qms.InspectionLotService;
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
 * 客户质量协议（change rework-inspection-tasks，spec customer-quality-agreement）：
 * 区间重叠 422、投诉登记留痕、停用不改历史、命中并入 CTQ/档位取严/投诉≥2 全检、清零恢复、
 * 无客户不误命中。
 */
@SpringBootTest
class CustomerAgreementDbTest {

    @Autowired
    private CustomerAgreementService service;
    @Autowired
    private InspectionLotService lotService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        purge();
        // 标准（AGR-ITEM 适用，OQC 建批需要）
        jdbc.update("INSERT INTO erp_qms_standard (ID, STANDARD_CODE, NAME, SCOPE_TYPE, RISK_LEVEL, STATUS, "
                + "CURRENT_VERSION, CREATE_BY, DEL_FLAG, VER_NO) VALUES ('std-agr-1','AGR-STD-1','协议测试标准',"
                + "'ITEM','C','ACTIVE',1,'junit','0',0)");
        jdbc.update("INSERT INTO erp_qms_standard_version (ID, STANDARD_ID, VERSION_NO, EFFECTIVE_FROM, "
                + "AQL_LEVEL, INSPECTION_LEVEL, STATUS, CHANGE_REASON, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                + "('sv-agr-1','std-agr-1',1,CURDATE(),'GB/T 2828.1 一般检验水平 II','II','RELEASED','初始','junit','0',0)");
        jdbc.update("INSERT INTO erp_qms_standard_applicability (ID, VERSION_ID, MATERIAL_CODE, PRIORITY, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES ('sap-agr-1','sv-agr-1','AGR-ITEM',10,'junit','0',0)");
        jdbc.update("INSERT INTO erp_qms_characteristic (ID, VERSION_ID, SEQUENCE_NO, CHARACTERISTIC_NAME, "
                + "CTQ_FLAG, SPEC_TYPE, METHOD_NAME, CREATE_BY, DEL_FLAG, VER_NO) VALUES ('chr-agr-1','sv-agr-1',1,"
                + "'外观','1','LOOK','目视','junit','0',0)");
        // 隔离质检员
        String roleId = jdbc.queryForObject(
                "SELECT ID FROM erp_admin_role WHERE ROLE_CODE='ROLE_INSPECTOR'", String.class);
        jdbc.update("INSERT IGNORE INTO erp_admin_user (ID, USERNAME, PASSWORD, NICK_NAME, STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES ('insp-agr', 'againsp', 'x', '协议质检', '1', 'junit', '0', 0)");
        jdbc.update("INSERT IGNORE INTO erp_admin_user_role (ID, USER_ID, ROLE_ID) VALUES ('urol-insp-agr','insp-agr',?)", roleId);
        jdbc.update("UPDATE erp_admin_user u SET u.STATUS='0' WHERE u.ID IN "
                + "(SELECT ur.USER_ID FROM erp_admin_user_role ur JOIN erp_admin_role r ON r.ID=ur.ROLE_ID "
                + "WHERE r.ROLE_CODE='ROLE_INSPECTOR') AND u.ID <> 'insp-agr'");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("UPDATE erp_admin_user u SET u.STATUS='1' WHERE u.ID IN "
                + "(SELECT ur.USER_ID FROM erp_admin_user_role ur JOIN erp_admin_role r ON r.ID=ur.ROLE_ID "
                + "WHERE r.ROLE_CODE='ROLE_INSPECTOR')");
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_qms_lot_item WHERE LOT_ID LIKE 'lot-agr-%'");
        jdbc.update("DELETE FROM erp_qms_inspection_lot WHERE ID LIKE 'lot-agr-%'");
        // 协议 ID 由 save 生成 UUID → 按编码前缀清
        jdbc.update("DELETE FROM erp_qms_agreement_log WHERE AGREEMENT_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_qms_customer_agreement WHERE AGREE_CODE LIKE 'AGR-%') t)");
        jdbc.update("DELETE FROM erp_qms_customer_agreement WHERE AGREE_CODE LIKE 'AGR-%'");
        jdbc.update("DELETE FROM erp_qms_characteristic WHERE ID='chr-agr-1'");
        jdbc.update("DELETE FROM erp_qms_standard_applicability WHERE ID='sap-agr-1'");
        jdbc.update("DELETE FROM erp_qms_standard_version WHERE ID='sv-agr-1'");
        jdbc.update("DELETE FROM erp_qms_standard WHERE ID='std-agr-1'");
        jdbc.update("DELETE FROM erp_admin_user_role WHERE USER_ID='insp-agr'");
        jdbc.update("DELETE FROM erp_admin_user WHERE ID='insp-agr'");
    }

    private CustomerAgreement newAgree(String code) {
        CustomerAgreement a = new CustomerAgreement();
        a.setAgreeCode(code);
        a.setCustomerId("CUST-AGR");
        a.setCustomerName("协议测试客户");
        a.setScopeType("ITEM");
        a.setScopeValue("AGR-ITEM");
        a.setEffectiveFrom(LocalDate.now().minusDays(1));
        return service.save(a);
    }

    // ---------- 3.1 协议维护 ----------

    @Test
    void overlapBlockedAndStopPreservesHistory() {
        CustomerAgreement a1 = newAgree("AGR-A1");
        service.activate(a1.getId());
        // 区间重叠 → 422
        CustomerAgreement a2 = newAgree("AGR-A2");
        ServiceException e = assertThrows(ServiceException.class, () -> service.activate(a2.getId()));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("重叠"), e.getMessage());
        // 停用不改历史：协议 STOPPED 后状态为 STOPPED，编码保留
        service.stop(a1.getId());
        String st = jdbc.queryForObject(
                "SELECT STATUS FROM erp_qms_customer_agreement WHERE ID=?", String.class, a1.getId());
        assertEquals("STOPPED", st);
        // 停用后新批不命中
        assertTrue(service.hitFor("CUST-AGR", "AGR-ITEM", null).isEmpty(), "STOPPED 不再命中");
    }

    @Test
    void complaintLogAndClear() {
        CustomerAgreement a = newAgree("AGR-C1");
        service.activate(a.getId());
        service.logComplaint(a.getId(), "外观划伤投诉");
        service.logComplaint(a.getId(), "包装破损投诉");
        Integer streak = jdbc.queryForObject(
                "SELECT COMPLAINT_STREAK FROM erp_qms_customer_agreement WHERE ID=?", Integer.class, a.getId());
        assertEquals(2, streak, "计数 +2");
        Integer logs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_qms_agreement_log WHERE AGREEMENT_ID=? AND ACTION='COMPLAINT'",
                Integer.class, a.getId());
        assertEquals(2, logs, "留痕两条");
        // 缺原因 422
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.logComplaint(a.getId(), "x"));
        assertEquals(422, e.getCode());
        // 清零恢复
        service.clearStreak(a.getId(), "问题关闭");
        Map<String, Object> hit = service.hitFor("CUST-AGR", "AGR-ITEM", null);
        assertEquals(Boolean.FALSE, hit.get("fullInspect"), "清零后不再全检");
    }

    // ---------- 3.2 命中加严 ----------

    @Test
    void hitMergesTightCtqAndFullInspect() {
        CustomerAgreement a = newAgree("AGR-H1");
        a.setTightCtqJson("[{\"name\":\"包装跌落\",\"specType\":\"LOOK\",\"method\":\"1m 跌落\"}]");
        a.setSampleLevelOverride("A"); // C 风险批 → A 档全检比例
        service.save(a);
        service.activate(a.getId());
        service.logComplaint(a.getId(), "第一次投诉");
        service.logComplaint(a.getId(), "第二次投诉");

        // OQC 建批（带客户）→ 命中：AGREE_CODE 快照 + CTQ 并入 + 全检
        var lot = lotService.createManual(Map.of(
                "lotType", "OQC", "itemCode", "AGR-ITEM", "itemName", "协议测试物料",
                "qty", BigDecimal.valueOf(50), "customerId", "CUST-AGR", "refId", "lot-agr-1"));
        assertEquals("AGR-H1", lot.getAgreeCode(), "协议快照");
        assertTrue(lot.getSampleBasis().contains("客户协议"), "命中依据留痕");
        assertTrue(lot.getSampleBasis().contains("全检"), "投诉≥2 全检留痕");
        assertEquals(0, lot.getSampleQty().compareTo(BigDecimal.valueOf(50)), "全检样本=批量");
        Integer ctqMerged = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_qms_lot_item WHERE LOT_ID=? AND CHARACTERISTIC_NAME='包装跌落' AND CTQ_FLAG='1'",
                Integer.class, lot.getId());
        assertEquals(1, ctqMerged, "加严 CTQ 并入且标 CTQ");
        // 原有 CTQ 保留
        Integer baseCtq = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_qms_lot_item WHERE LOT_ID=? AND CHARACTERISTIC_NAME='外观'",
                Integer.class, lot.getId());
        assertEquals(1, baseCtq, "原检验项保留");
    }

    @Test
    void noCustomerNoHit() {
        // 无客户 → 不误命中（spec 明确）
        var lot = lotService.createManual(Map.of(
                "lotType", "OQC", "itemCode", "AGR-ITEM", "itemName", "协议测试物料",
                "qty", BigDecimal.TEN, "refId", "lot-agr-2"));
        assertTrue(lot.getAgreeCode() == null || lot.getAgreeCode().isEmpty(), "无客户不命中");
        assertTrue(service.hitFor(null, "AGR-ITEM", null).isEmpty());
    }
}
