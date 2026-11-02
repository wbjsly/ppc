package com.erp.iot;

import com.erp.common.ServiceException;
import com.erp.service.iot.IotExceptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 13.4 异常上报与联动库测（add-iot-mobile，FR-4.11-3-x / BR-4.11-19~24）：
 * 质量冻结触发 / 设备故障建单幂等与 30min 升级 / 同班次≥3 合并升级 / IoT 来源现场确认门禁。
 */
@SpringBootTest
class IotExceptionDbTest {

    @Autowired
    private IotExceptionService service;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String P = "iot-exc-";
    private String deviceNo;
    private String itemCode;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-admin", null, List.of(
                        new SimpleGrantedAuthority("ROLE_QUALITY_ENG"),
                        new SimpleGrantedAuthority("ROLE_EQUIP_ENG"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))));
        purge();
        String n = UUID.randomUUID().toString().substring(0, 8);
        deviceNo = P + "DEV-" + n;
        itemCode = P + "IT-" + n;
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, " +
                        "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, STATUS, CREATE_BY) " +
                        "VALUES (?,?,?,'0001','PC','STRUCT','BUY','NORMAL','1','1','junit')",
                P + "it-id", itemCode, "异常联动测试物料");
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, " +
                        "BIN_CODE, QTY, AVAILABLE_QTY, CREATE_BY) VALUES (?,?,?,?,'" + P + "B1','BIN-A01',50,50,'junit')",
                P + "st-id", "WH-MAIN", itemCode, "异常联动测试物料");
        jdbc.update("INSERT INTO erp_iot_device (ID, DEVICE_NO, DEVICE_TYPE, PROTOCOL, " +
                        "PROTOCOL_PRIORITY, SAMPLE_FREQ_SEC, STATUS, CERT_STATUS, CREATE_DATE, DEL_FLAG, VER_NO) " +
                        "VALUES (?,?, 'SENSOR', 'MQTT', 2, 60, 'ONLINE','ACTIVE', NOW(),'0',0)",
                P + "dev-id", deviceNo);
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_iot_alarm_event WHERE DEVICE_NO LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_iot_mobile_todo WHERE REF_ID IN (SELECT ID FROM erp_iot_exception WHERE DEVICE_NO LIKE ?)", P + "%");
        jdbc.update("DELETE FROM erp_iot_exception WHERE DEVICE_NO LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_eam_work_order WHERE PLAN_DESC LIKE ?", "%iot-exc-%");
        jdbc.update("DELETE FROM erp_inv_freeze WHERE ITEM_CODE LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_iot_device WHERE DEVICE_NO LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE LIKE ?", P + "%");
    }

    private Map<String, Object> body(String type, String severity, String source) {
        Map<String, Object> b = new HashMap<>();
        b.put("excType", type);
        b.put("severity", severity);
        b.put("source", source);
        b.put("deviceNo", deviceNo);
        b.put("title", "异常联动测试");
        return b;
    }

    @Test
    void qualityExceptionTriggersImmediateFreeze() {
        Map<String, Object> b = body("QUALITY", "SEVERE", "MANUAL");
        b.put("itemCode", itemCode);
        b.put("description", "外观不良 [batch:" + P + "B1]");
        Map<String, Object> r = service.report(b);
        assertTrue(String.valueOf(r.get("linkage")).startsWith("QUALITY_FREEZE"),
                "质量异常即时冻结联动");
        // 冻结台账 ACTIVE（NCR 来源立即生效无审批）
        Integer fz = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_inv_freeze WHERE ITEM_CODE=? AND STATUS='ACTIVE'",
                Integer.class, itemCode);
        assertEquals(1, fz, "质量冻结台账落库");
    }

    @Test
    void equipmentExceptionCreatesWoIdempotent() {
        Map<String, Object> r = service.report(body("EQUIPMENT", "SEVERE", "MANUAL"));
        String linkage = String.valueOf(r.get("linkage"));
        assertTrue(linkage.startsWith("EAM_WO:"), "设备故障联动建单");
        // 设备故障态
        String devStatus = jdbc.queryForObject(
                "SELECT STATUS FROM erp_iot_device WHERE DEVICE_NO=?", String.class, deviceNo);
        assertEquals("FAULT", devStatus, "设备置故障态");
        // 幂等：同异常再触发联动不重复建单（confirm 二次触发被 409 拦，直接验证建单数=1）
        Integer woCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_eam_work_order WHERE IDEMPOTENT_KEY=?",
                Integer.class, "IOT-EXC:" + r.get("id"));
        assertEquals(1, woCnt, "幂等键建单仅一张");
    }

    @Test
    void safetyExceptionRecordsStopCommand() {
        Map<String, Object> r = service.report(body("SAFETY", "URGENT", "MANUAL"));
        String linkage = String.valueOf(r.get("linkage"));
        assertTrue(linkage.startsWith("SAFETY_STOP_CMD"), "安全事件停机指令打点");
        // 时效打点落库
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT STOP_CMD_AT, STOP_ACK_MS FROM erp_iot_exception WHERE ID=?", r.get("id"));
        assertNotNull(row.get("STOP_CMD_AT"), "停机指令下发时间打点");
        assertNotNull(row.get("STOP_ACK_MS"), "下发到确认时效记录");
        // 紧急级即时通知车间主任
        assertNotNull(r.get("urgentNotified"), "紧急异常即时通知");
    }

    @Test
    void shiftDuplicatesMergeAndEscalate() {
        // 同设备同类型第 1/2/3 次：第 2 次起合并，第 3 次升级 SEVERE（BR-4.11-24）
        Map<String, Object> r1 = service.report(body("EQUIPMENT", "GENERAL", "MANUAL"));
        assertEquals(Boolean.FALSE, r1.get("merged"), "首次不合并");
        Map<String, Object> r2 = service.report(body("EQUIPMENT", "GENERAL", "MANUAL"));
        assertEquals(Boolean.TRUE, r2.get("merged"), "同班次同类第二次合并");
        Map<String, Object> r3 = service.report(body("EQUIPMENT", "GENERAL", "MANUAL"));
        assertEquals(Boolean.TRUE, r3.get("merged"));
        assertEquals("SEVERE", r3.get("severity"), "≥3 次升级严重程度");
        // 合并链锚到首单
        String mergeOf = jdbc.queryForObject(
                "SELECT MERGE_OF FROM erp_iot_exception WHERE ID=?", String.class, r3.get("id"));
        assertEquals(r1.get("id"), mergeOf, "合并锚定首单");
    }

    @Test
    void iotAutoReportGatedUntilFieldConfirm() {
        Map<String, Object> r = service.report(body("SAFETY", "URGENT", "IOT"));
        assertEquals(Boolean.TRUE, r.get("pendingConfirm"), "IoT 自动上报待现场确认");
        assertEquals("GATED_AWAIT_FIELD_CONFIRM", r.get("linkage"), "重动作被门禁不触发");
        // 现场确认后联动触发
        Map<String, Object> c = service.confirm((String) r.get("id"));
        assertEquals(Boolean.TRUE, c.get("confirmed"));
        assertTrue(String.valueOf(c.get("linkage")).startsWith("SAFETY_STOP_CMD"),
                "确认后触发停机联动");
        // 非门禁单重复确认 409
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.confirm((String) r.get("id")));
        assertEquals(409, ex.getCode());
    }

    @Test
    void overdueEscalatesToSupervisor() {
        Map<String, Object> r = service.report(body("EQUIPMENT", "SEVERE", "MANUAL"));
        // 模拟超时：DUE_AT 拨回 31 分钟前
        jdbc.update("UPDATE erp_iot_exception SET DUE_AT=DATE_SUB(NOW(), INTERVAL 31 MINUTE) WHERE ID=?",
                r.get("id"));
        Map<String, Object> out = service.scanEscalate();
        assertTrue(((Number) out.get("escalated")).intValue() >= 1, "30min 未响应升级");
        // 升级留痕
        String remark = jdbc.queryForObject(
                "SELECT REMARK FROM erp_iot_exception WHERE ID=?", String.class, r.get("id"));
        assertTrue(remark != null && remark.contains("升级"), "升级链留痕");
    }

    @Test
    void disposeClosesExceptionAndAlarm() {
        Map<String, Object> r = service.report(body("EQUIPMENT", "SEVERE", "MANUAL"));
        Map<String, Object> d = service.dispose((String) r.get("id"), "恢复", "更换轴承完成");
        assertEquals(Boolean.TRUE, d.get("closed"));
        // 告警事件镜像闭环
        Integer closed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_iot_alarm_event WHERE REMARK=? AND STATUS='CLOSED'",
                Integer.class, r.get("excNo"));
        assertEquals(1, closed, "处置闭环回写告警事件");
    }
}
