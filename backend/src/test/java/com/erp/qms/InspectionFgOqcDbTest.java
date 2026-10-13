package com.erp.qms;

import com.erp.common.ServiceException;
import com.erp.entity.qms.InspectionLot;
import com.erp.service.qms.InspectionLotService;
import com.erp.service.qms.QualityGateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OQC 完工全链（change rework-inspection-tasks，spec inspection-lot ADDED +
 * completion-backflush / inbound-workbench / sales-shipment ADDED）：
 * 完工建批（BLOCKED 不阻断）、放行联动 WIP_IN 入库、未放行无库存、合格证、
 * 冲销取消未结论批、发货确认 OQC 闸门四分支。
 */
@SpringBootTest
class InspectionFgOqcDbTest {

    @Autowired
    private InspectionLotService lotService;
    @Autowired
    private QualityGateService qualityGateService;
    @Autowired
    private com.erp.service.SysParamService sysParam;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        purge();
        // 产品标准（FG-ITEM 适用）
        jdbc.update("INSERT INTO erp_qms_standard (ID, STANDARD_CODE, NAME, SCOPE_TYPE, RISK_LEVEL, STATUS, "
                + "CURRENT_VERSION, CREATE_BY, DEL_FLAG, VER_NO) VALUES ('std-fg-1','FG-STD-1','完工测试标准',"
                + "'ITEM','C','ACTIVE',1,'junit','0',0)");
        jdbc.update("INSERT INTO erp_qms_standard_version (ID, STANDARD_ID, VERSION_NO, EFFECTIVE_FROM, "
                + "AQL_LEVEL, INSPECTION_LEVEL, STATUS, CHANGE_REASON, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                + "('sv-fg-1','std-fg-1',1,CURDATE(),'GB/T 2828.1 一般检验水平 II','II','RELEASED','初始','junit','0',0)");
        jdbc.update("INSERT INTO erp_qms_standard_applicability (ID, VERSION_ID, MATERIAL_CODE, PRIORITY, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES ('sap-fg-1','sv-fg-1','FG-ITEM',10,'junit','0',0)");
        jdbc.update("INSERT INTO erp_qms_characteristic (ID, VERSION_ID, SEQUENCE_NO, CHARACTERISTIC_NAME, "
                + "CTQ_FLAG, SPEC_TYPE, METHOD_NAME, CREATE_BY, DEL_FLAG, VER_NO) VALUES ('chr-fg-1','sv-fg-1',1,"
                + "'外观','1','LOOK','目视','junit','0',0)");
        // 无标准产品（BLOCKED 场景）
        // 质检员
        String roleId = jdbc.queryForObject(
                "SELECT ID FROM erp_admin_role WHERE ROLE_CODE='ROLE_INSPECTOR'", String.class);
        jdbc.update("INSERT IGNORE INTO erp_admin_user (ID, USERNAME, PASSWORD, NICK_NAME, STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES ('insp-fg', 'fginsp', 'x', '完工质检', '1', 'junit', '0', 0)");
        jdbc.update("INSERT IGNORE INTO erp_admin_user_role (ID, USER_ID, ROLE_ID) VALUES ('urol-insp-fg','insp-fg',?)", roleId);
        jdbc.update("UPDATE erp_admin_user u SET u.STATUS='0' WHERE u.ID IN "
                + "(SELECT ur.USER_ID FROM erp_admin_user_role ur JOIN erp_admin_role r ON r.ID=ur.ROLE_ID "
                + "WHERE r.ROLE_CODE='ROLE_INSPECTOR') AND u.ID <> 'insp-fg'");
        // 放行需质检员鉴权上下文
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.TestingAuthenticationToken(
                        "insp-fg", null, "ROLE_INSPECTOR"));
    }

    @AfterEach
    void cleanup() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        jdbc.update("UPDATE erp_admin_user u SET u.STATUS='1' WHERE u.ID IN "
                + "(SELECT ur.USER_ID FROM erp_admin_user_role ur JOIN erp_admin_role r ON r.ID=ur.ROLE_ID "
                + "WHERE r.ROLE_CODE='ROLE_INSPECTOR')");
        purge();
    }

    private void purge() {
        // 批号按行数生成会跨用例复用 → 清全部 MO_FINISH 流水防撞锚（测试库隔离）
        jdbc.update("DELETE FROM erp_inv_transaction WHERE BIZ_DOC_TYPE='MO_FINISH'");
        jdbc.update("DELETE FROM erp_qms_certificate WHERE LOT_NO LIKE 'IL-FG%' OR LOT_ID LIKE 'lot-fg-%'");
        jdbc.update("DELETE FROM erp_qms_lot_item WHERE LOT_ID LIKE 'lot-fg-%'");
        jdbc.update("DELETE FROM erp_qms_inspection_lot WHERE ID LIKE 'lot-fg-%'");
        jdbc.update("DELETE FROM erp_qms_characteristic WHERE ID='chr-fg-1'");
        jdbc.update("DELETE FROM erp_qms_standard_applicability WHERE ID='sap-fg-1'");
        jdbc.update("DELETE FROM erp_qms_standard_version WHERE ID='sv-fg-1'");
        jdbc.update("DELETE FROM erp_qms_standard WHERE ID='std-fg-1'");
        jdbc.update("DELETE FROM erp_admin_user_role WHERE USER_ID='insp-fg'");
        jdbc.update("DELETE FROM erp_admin_user WHERE ID='insp-fg'");
    }

    /** 造完工批并重写为确定 ID（清理锚） */
    private InspectionLot fgLot(String code, String itemCode) {
        InspectionLot lot = lotService.createFinishedGoodsOqc("mo-fg-" + code, itemCode,
                "完工测试产品", null, BigDecimal.valueOf(20), null);
        String oldId = lot.getId();
        if (!oldId.startsWith("lot-fg-")) {
            jdbc.update("UPDATE erp_qms_lot_item SET LOT_ID=? WHERE LOT_ID=?", "lot-fg-" + code, oldId);
            jdbc.update("UPDATE erp_qms_inspection_lot SET ID=? WHERE ID=?", "lot-fg-" + code, oldId);
            lot.setId("lot-fg-" + code);
        }
        return lot;
    }

    /** 判定合格并放行走完整录入链 */
    private void judgeAndRelease(String lotId) {
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT ID, CTQ_FLAG, SPEC_TYPE FROM erp_qms_lot_item WHERE LOT_ID=?", lotId);
        for (Map<String, Object> it : items) {
            jdbc.update("UPDATE erp_qms_lot_item SET JUDGE='PASS' WHERE ID=?", it.get("ID"));
        }
        jdbc.update("UPDATE erp_qms_inspection_lot SET RESULT='PASS', STATUS='INPUTTING' WHERE ID=?", lotId);
        lotService.confirmRelease(lotId);
    }

    // ---------- 4.1 完工建批 ----------

    @Test
    void fgLotCreatedOnCompletionAndIdempotent() {
        InspectionLot lot = fgLot("1", "FG-ITEM");
        assertEquals("OQC", lot.getLotType());
        assertTrue(lot.getRefId().endsWith("#FG"));
        assertEquals("PENDING", lot.getStatus());
        assertNotNull(lot.getInspectorId(), "建批自动分派");
        // 幂等：重复完工触发返回既有批
        InspectionLot again = lotService.createFinishedGoodsOqc("mo-fg-1", "FG-ITEM",
                "完工测试产品", null, BigDecimal.valueOf(20), null);
        assertEquals(lot.getId(), again.getId());
    }

    @Test
    void noStandardBlockedButNotThrowing() {
        InspectionLot lot = fgLot("2", "FG-NOSTD");
        assertEquals("BLOCKED", lot.getStatus(), "无标准 BLOCKED 占位不抛错（不阻断完工）");
        assertTrue(lot.getSampleBasis().contains("无生效检验标准"));
    }

    // ---------- 4.2 冲销取消 ----------

    @Test
    void reversalCancelsUnconcludedFgLots() {
        fgLot("3", "FG-ITEM");
        int cancelled = lotService.cancelFgLotsForMo("mo-fg-3");
        assertEquals(1, cancelled, "未结论批被取消");
        String st = jdbc.queryForObject(
                "SELECT STATUS FROM erp_qms_inspection_lot WHERE REF_ID='mo-fg-3#FG'", String.class);
        assertEquals("CANCELLED", st);
    }

    // ---------- 4.3 + 5.1 放行联动入库与合格证 ----------

    @Test
    void releaseTriggersStockInAndCertificate() {
        InspectionLot lot = fgLot("4", "FG-ITEM");
        // 未放行无库存
        Integer preTxn = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_inv_transaction WHERE BIZ_DOC_TYPE='MO_FINISH' AND BIZ_DOC_NO=?",
                Integer.class, lot.getLotNo());
        assertEquals(0, preTxn, "未放行不产生入库流水");
        judgeAndRelease(lot.getId());
        String st = jdbc.queryForObject(
                "SELECT STATUS FROM erp_qms_inspection_lot WHERE ID=?", String.class, lot.getId());
        assertEquals("RELEASED", st);
        // WIP_IN 流水落库（联动入库）
        Integer txn = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_inv_transaction WHERE BIZ_DOC_TYPE='MO_FINISH' AND BIZ_DOC_NO=? "
                        + "AND TYPE_CODE='WIP_IN'", Integer.class, lot.getLotNo());
        assertEquals(1, txn, "放行联动 WIP_IN 入库流水");
        // 合格证
        Integer cert = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_qms_certificate WHERE LOT_NO=?", Integer.class, lot.getLotNo());
        assertEquals(1, cert, "OQC 放行生成合格证");
        String certNo = jdbc.queryForObject(
                "SELECT CERT_NO FROM erp_qms_certificate WHERE LOT_NO=?", String.class, lot.getLotNo());
        assertTrue(certNo.startsWith("C"), "合格证号 C 前缀");
        // 放行不可撤回（既有语义）
        ServiceException e = assertThrows(ServiceException.class,
                () -> lotService.confirmRelease(lot.getId()));
        assertEquals(422, e.getCode());
    }

    @Test
    void releasePrecheckFailureKeepsLotUnreleased() {
        InspectionLot lot = fgLot("5", "FG-ITEM");
        // CTQ 未录 → 放行前置校验阻断（间接验证联动失败不入库）
        assertThrows(ServiceException.class, () -> lotService.confirmRelease(lot.getId()));
        Integer txn = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_inv_transaction WHERE BIZ_DOC_TYPE='MO_FINISH' AND BIZ_DOC_NO=?",
                Integer.class, lot.getLotNo());
        assertEquals(0, txn, "未放行不入库");
        Integer cert = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_qms_certificate WHERE LOT_NO=?", Integer.class, lot.getLotNo());
        assertEquals(0, cert, "未放行无合格证");
    }

    // ---------- 5.2 发货确认 OQC 闸门 ----------

    @Test
    void shipmentGateBlocksUnreleasedAndHandlesEnforceSwitch() {
        // 场景 1：有批未放行 → 422 阻断
        fgLot("6", "FG-ITEM");
        ServiceException e = assertThrows(ServiceException.class, () ->
                qualityGateService.assertOqcForShipment(List.of(
                        Map.of("itemCode", "FG-ITEM", "batchNo", ""))));
        assertTrue(e.getMessage().contains("未放行"), e.getMessage());
        // 场景 2：无批 + ON → 阻断
        ServiceException e2 = assertThrows(ServiceException.class, () ->
                qualityGateService.assertOqcForShipment(List.of(
                        Map.of("itemCode", "FG-NOLOT", "batchNo", ""))));
        assertTrue(e2.getMessage().contains("无 OQC"), e2.getMessage());
        // 场景 3：无批 + OFF → 降级 warnings（参数须走服务写入——进程缓存，SQL 直改不生效）
        sysParam.setValue("OQC_ENFORCE", "OFF", "STRING", "QMS", "测试降级", "junit");
        try {
            Map<String, Object> degraded = qualityGateService.assertOqcForShipment(List.of(
                    Map.of("itemCode", "FG-NOLOT", "batchNo", "")));
            assertEquals("DEGRADED", degraded.get("decision"));
            @SuppressWarnings("unchecked")
            List<String> warnings = (List<String>) degraded.get("warnings");
            assertEquals(1, warnings.size());
        } finally {
            sysParam.setValue("OQC_ENFORCE", "ON", "STRING", "QMS", "测试复位", "junit");
        }
        // 场景 4：免检 SKIPPED 视同放行
        jdbc.update("INSERT INTO erp_qms_inspection_lot (ID, LOT_NO, LOT_TYPE, SOURCE_TYPE, ITEM_CODE, ITEM_NAME, "
                + "LOT_QTY, RISK_GRADE, STATUS, RESULT, PRIORITY, STRICTNESS, EXEMPT_FLAG, OVERDUE_FLAG, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES ('lot-fg-skip','IL-FG-SKIP','OQC','MANUAL','FG-SKIP','免检产品',"
                + "5,'C','SKIPPED','PENDING','NORMAL','NORMAL','1','0','junit','0',0)");
        Map<String, Object> allowed = qualityGateService.assertOqcForShipment(List.of(
                Map.of("itemCode", "FG-SKIP", "batchNo", "")));
        assertEquals("ALLOWED", allowed.get("decision"), "免检视同放行");
    }
}
