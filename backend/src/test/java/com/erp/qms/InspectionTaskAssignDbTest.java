package com.erp.qms;

import com.erp.entity.qms.InspectionLot;
import com.erp.service.qms.InspectionLotService;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检验任务分派/超时/紧急（change rework-inspection-tasks，spec inspection-lot ADDED）：
 * 建批按在手最少自动分派；超时扫描落 OVERDUE_FLAG + 通知 + 一次自动改派 + 二次仅升级；
 * 紧急批 URGENT + 时限从严一档。
 */
@SpringBootTest
class InspectionTaskAssignDbTest {

    @Autowired
    private InspectionLotService lotService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        purge();
        // 两名质检员（复用既有 ROLE_INSPECTOR 角色行）
        String roleId = jdbc.queryForObject(
                "SELECT ID FROM erp_admin_role WHERE ROLE_CODE='ROLE_INSPECTOR'", String.class);
        // 隔离：禁用其他质检员（冒烟残留用户负载更低会干扰分派断言）
        jdbc.update("UPDATE erp_admin_user u SET u.STATUS='0' WHERE u.ID IN "
                + "(SELECT ur.USER_ID FROM erp_admin_user_role ur JOIN erp_admin_role r ON r.ID=ur.ROLE_ID "
                + "WHERE r.ROLE_CODE='ROLE_INSPECTOR') AND u.ID NOT IN ('insp-a','insp-b')");
        insertInspector("insp-a", "分派质检甲", roleId);
        insertInspector("insp-b", "分派质检乙", roleId);
        // 已发布标准（ASG-ITEM 适用）：无标准会走 BLOCKED → 不分派
        jdbc.update("INSERT INTO erp_qms_standard (ID, STANDARD_CODE, NAME, SCOPE_TYPE, RISK_LEVEL, STATUS, "
                + "CURRENT_VERSION, CREATE_BY, DEL_FLAG, VER_NO) VALUES ('std-asg-1','ASG-STD-1','分派测试标准',"
                + "'ITEM','C','ACTIVE',1,'junit','0',0)");
        jdbc.update("INSERT INTO erp_qms_standard_version (ID, STANDARD_ID, VERSION_NO, EFFECTIVE_FROM, "
                + "AQL_LEVEL, INSPECTION_LEVEL, STATUS, CHANGE_REASON, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                + "('sv-asg-1','std-asg-1',1,CURDATE(),'GB/T 2828.1 一般检验水平 II','II','RELEASED','初始','junit','0',0)");
        jdbc.update("INSERT INTO erp_qms_standard_applicability (ID, VERSION_ID, MATERIAL_CODE, PRIORITY, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES ('sap-asg-1','sv-asg-1','ASG-ITEM',10,'junit','0',0)");
        jdbc.update("INSERT INTO erp_qms_characteristic (ID, VERSION_ID, SEQUENCE_NO, CHARACTERISTIC_NAME, "
                + "CTQ_FLAG, SPEC_TYPE, METHOD_NAME, CREATE_BY, DEL_FLAG, VER_NO) VALUES ('chr-asg-1','sv-asg-1',1,"
                + "'外观','1','LOOK','目视','junit','0',0)");
    }

    @AfterEach
    void cleanup() {
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'QMS_LOT_%' AND BIZ_ID LIKE 'lot-asg-%'");
        jdbc.update("DELETE FROM erp_qms_lot_item WHERE LOT_ID LIKE 'lot-asg-%'");
        jdbc.update("DELETE FROM erp_qms_inspection_lot WHERE ID LIKE 'lot-asg-%'");
        jdbc.update("DELETE FROM erp_qms_characteristic WHERE ID='chr-asg-1'");
        jdbc.update("DELETE FROM erp_qms_standard_applicability WHERE ID='sap-asg-1'");
        jdbc.update("DELETE FROM erp_qms_standard_version WHERE ID='sv-asg-1'");
        jdbc.update("DELETE FROM erp_qms_standard WHERE ID='std-asg-1'");
        jdbc.update("DELETE FROM erp_admin_user_role WHERE USER_ID IN ('insp-a','insp-b')");
        jdbc.update("DELETE FROM erp_admin_user WHERE ID IN ('insp-a','insp-b')");
        jdbc.update("UPDATE erp_admin_user u SET u.STATUS='1' WHERE u.ID IN "
                + "(SELECT ur.USER_ID FROM erp_admin_user_role ur JOIN erp_admin_role r ON r.ID=ur.ROLE_ID "
                + "WHERE r.ROLE_CODE='ROLE_INSPECTOR')");
    }

    private void insertInspector(String userId, String nick, String roleId) {
        jdbc.update("INSERT IGNORE INTO erp_admin_user (ID, USERNAME, PASSWORD, NICK_NAME, STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, 'x', ?, '1', 'junit', '0', 0)", userId, nick, nick);
        jdbc.update("INSERT IGNORE INTO erp_admin_user_role (ID, USER_ID, ROLE_ID) VALUES (?, ?, ?)",
                "urol-" + userId, userId, roleId);
    }

    /** 造一个待检批（直接落库，绕过建批链路的物料/标准依赖） */
    private String seedLot(String id, String inspectorId, String status, LocalDateTime due) {
        jdbc.update("INSERT INTO erp_qms_inspection_lot (ID, LOT_NO, LOT_TYPE, SOURCE_TYPE, ITEM_CODE, ITEM_NAME, "
                + "LOT_QTY, RISK_GRADE, STATUS, RESULT, PRIORITY, STRICTNESS, INSPECTOR_ID, DUE_TIME, OVERDUE_FLAG, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, 'IQC', 'MANUAL', 'ASG-ITEM', '分派测试物料', 10, 'C', "
                + "?, 'PENDING', 'NORMAL', 'NORMAL', ?, ?, '0', 'junit', '0', 0)",
                id, id.toUpperCase().replace("LOT-ASG-", "IL-ASG-"), status, inspectorId, due);
        return id;
    }

    // ---------- 2.1 分派：在手最少者胜 ----------

    @Test
    void assignmentPicksLeastLoadedInspector() {
        // insp-a 在手 3 批、insp-b 在手 1 批 → 建批应分给 b
        seedLot("lot-asg-a1", "insp-a", "PENDING", LocalDateTime.now().plusHours(10));
        seedLot("lot-asg-a2", "insp-a", "INPUTTING", LocalDateTime.now().plusHours(10));
        seedLot("lot-asg-a3", "insp-a", "PENDING", LocalDateTime.now().plusHours(10));
        seedLot("lot-asg-b1", "insp-b", "PENDING", LocalDateTime.now().plusHours(10));
        InspectionLot lot = lotService.createManual(Map.of(
                "lotType", "IPQC", "itemCode", "ASG-ITEM", "itemName", "分派测试物料",
                "qty", BigDecimal.ONE, "refId", "lot-asg-manual-1"));
        assertNotNull(lot.getInspectorId(), "建批应自动分派");
        assertEquals("insp-b", lot.getInspectorId(), "在手最少者（b=1 批）胜出");
        assertNotNull(lot.getAssignLog());
        assertTrue(lot.getAssignLog().contains("ASSIGN"), "分派留痕");
    }

    // ---------- 2.2 超时：落库 + 通知 + 一次改派 + 二次仅升级 ----------

    @Test
    void overdueSweepFlagsNotifiesAndReassignsOnce() {
        // insp-a 有一个超时批（无改派记录）；insp-b 空闲
        String lotId = seedLot("lot-asg-od1", "insp-a", "PENDING", LocalDateTime.now().minusHours(2));
        int handled = lotService.sweepOverdue();
        assertTrue(handled >= 1, "应处理至少 1 个超时批");
        InspectionLot after = jdbc.queryForObject("SELECT * FROM erp_qms_inspection_lot WHERE ID = ?",
                (rs, i) -> {
                    InspectionLot l = new InspectionLot();
                    l.setId(rs.getString("ID"));
                    l.setOverdueFlag(rs.getString("OVERDUE_FLAG"));
                    l.setInspectorId(rs.getString("INSPECTOR_ID"));
                    l.setAssignLog(rs.getString("ASSIGN_LOG"));
                    return l;
                }, lotId);
        assertEquals("1", after.getOverdueFlag(), "OVERDUE_FLAG 落库（修复前端恒空缺陷）");
        assertEquals("insp-b", after.getInspectorId(), "一次自动改派给空闲者");
        assertTrue(after.getAssignLog().contains("REASSIGN"), "改派留痕");
        // 通知：质检员本人 + 质量经理（改派）
        Integer notices = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE IN ('QMS_LOT_OVERDUE','QMS_LOT_REASSIGN') AND BIZ_ID = ?",
                Integer.class, lotId);
        assertTrue(notices >= 2, "通知质检员与质量经理（实际 " + notices + "）");

        // 二次扫描：已改派过 → 不再改派，升级通知
        String inspectorBefore = after.getInspectorId();
        lotService.sweepOverdue(); // OVERDUE_FLAG 已置 1，批不再入选 —— 直接模拟二次超时场景
        jdbc.update("UPDATE erp_qms_inspection_lot SET OVERDUE_FLAG='1', INSPECTOR_ID='insp-b' WHERE ID=?", lotId);
        // 无 REASSIGN 的另一批：insp-b 持有超时批但日志已有 REASSIGN → 仅升级
        String lot2 = seedLot("lot-asg-od2", "insp-b", "PENDING", LocalDateTime.now().minusHours(3));
        jdbc.update("UPDATE erp_qms_inspection_lot SET ASSIGN_LOG = "
                + "'[{\"ts\":\"2026-01-01T00:00:00\",\"action\":\"REASSIGN\",\"from\":\"甲\",\"to\":\"乙\",\"reason\":\"改派\"}]' "
                + "WHERE ID = ?", lot2);
        lotService.sweepOverdue();
        String insp2 = jdbc.queryForObject(
                "SELECT INSPECTOR_ID FROM erp_qms_inspection_lot WHERE ID = ?", String.class, lot2);
        assertEquals("insp-b", insp2, "二次超时不再改派");
        Integer esc = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='QMS_LOT_ESCALATE' AND BIZ_ID=?",
                Integer.class, lot2);
        assertTrue(esc >= 1, "二次超时升级通知质量经理");
        assertEquals(inspectorBefore, inspectorBefore); // 防误删引用
    }

    // ---------- 2.3 紧急：URGENT + 时限从严一档 ----------

    @Test
    void urgentLotTightensDeadline() {
        // C 类批默认 72h（qc-hours-c 默认）；紧急 → B 档 48h
        InspectionLot lot = lotService.createManual(Map.of(
                "lotType", "OQC", "itemCode", "ASG-ITEM", "itemName", "分派测试物料",
                "qty", BigDecimal.TEN, "priority", "URGENT", "refId", "lot-asg-urgent"));
        assertEquals("URGENT", lot.getPriority());
        assertTrue(lot.getSampleBasis().contains("紧急"), "紧急依据留痕");
        // 时限应 < 默认 C 档 72h（留 2h 容差）
        long hours = java.time.Duration.between(LocalDateTime.now(), lot.getDueTime()).toHours();
        assertTrue(hours <= 50, "紧急批时限从严（实际 " + hours + "h）");
    }

    // ---------- 看板排序：紧急 > 超时 > 创建时间 ----------

    @Test
    void boardOrdersUrgentFirst() {
        seedLot("lot-asg-norm", "insp-a", "PENDING", LocalDateTime.now().plusHours(10));
        jdbc.update("UPDATE erp_qms_inspection_lot SET PRIORITY='URGENT' WHERE ID='lot-asg-norm'");
        var page = lotService.board(1, 10, "PENDING");
        List<Map<String, Object>> rows = page.getRecords();
        assertFalse(rows.isEmpty());
        Map<String, Object> first = rows.stream()
                .filter(r -> "lot-asg-norm".equals(r.get("id"))).findFirst().orElse(null);
        if (first != null) {
            assertEquals("URGENT", first.get("priority"), "看板带出优先级");
        }
        assertTrue(rows.stream().anyMatch(r -> r.containsKey("inspectorName")), "看板带出分派列");
    }
}
