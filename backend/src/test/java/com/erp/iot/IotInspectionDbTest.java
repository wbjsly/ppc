package com.erp.iot;

import com.erp.common.ServiceException;
import com.erp.service.iot.IotInspectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 13.3 设备巡检执行库测（add-iot-mobile，S-4-11-06）：
 * 计划到期生成 / 签到落库与设备校验 / 逐项点检 / 异常标记联动 / 缺图补拍 / 超时提醒。
 */
@SpringBootTest
class IotInspectionDbTest {

    @Autowired
    private IotInspectService service;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String P = "iot-insp-";
    private String deviceNo;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-admin", null, List.of(
                        new SimpleGrantedAuthority("ROLE_EQUIPMENT"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))));
        purge();
        deviceNo = P + "DEV-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_iot_exception WHERE DEVICE_NO LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_iot_inspect_record WHERE DEVICE_NO LIKE ? OR PLAN_CODE LIKE ?", P + "%", P + "%");
        jdbc.update("DELETE FROM erp_qms_patrol_plan WHERE PLAN_CODE LIKE ?", P + "%");
    }

    private void seedPlan(LocalDateTime nextRun) {
        jdbc.update("INSERT INTO erp_qms_patrol_plan (ID, PLAN_CODE, NAME, LOT_TYPE, ITEM_CODE, " +
                        "ITEM_NAME, INTERVAL_MINUTES, LOT_QTY, NEXT_RUN_TIME, STATUS, CREATE_BY, " +
                        "CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?,?,?, 'IPQC', ?, '巡检设备', 120, 1, ?, 'ACTIVE', 'junit', NOW(), '0', 0)",
                P + "plan-id", P + "PLAN-1", "设备巡检计划", deviceNo, nextRun);
    }

    @Test
    void duePlanGeneratesTaskOnce() {
        seedPlan(LocalDateTime.now().minusMinutes(5));
        Map<String, Object> r1 = service.generateFromPlans();
        assertEquals(1, ((Number) r1.get("created")).intValue(), "到期计划生成任务");
        // 未闭环不重复生成
        Map<String, Object> r2 = service.generateFromPlans();
        assertEquals(0, ((Number) r2.get("created")).intValue(), "同计划未闭环不重复生成");
        // 计划下次时间已推进
        LocalDateTime next = jdbc.queryForObject(
                "SELECT NEXT_RUN_TIME FROM erp_qms_patrol_plan WHERE PLAN_CODE=?",
                LocalDateTime.class, P + "PLAN-1");
        assertTrue(next.isAfter(LocalDateTime.now()), "计划 NEXT_RUN_TIME 已推进");
    }

    @Test
    void checkinLandsAndDeviceMismatchBlocks() {
        Map<String, Object> r = service.create(Map.of("deviceNo", deviceNo));
        String id = (String) r.get("id");
        // 设备一致签到
        Map<String, Object> ok = service.checkin(id, deviceNo);
        assertNotNull(ok.get("checkinAt"), "签到时间落库");
        assertEquals("user-admin", ok.get("checkinBy"));
        // 不一致 L1 拒绝
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.checkin(id, P + "OTHER-DEV"));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("L1"));
    }

    @Test
    void submitRecordsItemsAndPersistsPhotos() {
        Map<String, Object> r = service.create(Map.of("deviceNo", deviceNo));
        String id = (String) r.get("id");
        String items = "[{\"item\":\"温度\",\"value\":\"42℃\",\"abnormal\":false},"
                + "{\"item\":\"振动\",\"value\":\"偏高\",\"abnormal\":false}]";
        Map<String, Object> s = service.submit(id, items, "[\"/uploads/insp-1.jpg\"]");
        assertEquals("SUBMITTED", s.get("status"));
        assertEquals("0", s.get("abnormalFlag"));
        assertEquals("[\"/uploads/insp-1.jpg\"]", s.get("photoUrls"), "拍照留证持久化");
        // 回读确认落库
        String stored = jdbc.queryForObject(
                "SELECT PHOTO_URLS FROM erp_iot_inspect_record WHERE ID=?", String.class, id);
        assertEquals("[\"/uploads/insp-1.jpg\"]", stored);
    }

    @Test
    void abnormalWithoutPhotoBlockedWithHint() {
        Map<String, Object> r = service.create(Map.of("deviceNo", deviceNo));
        String items = "[{\"item\":\"异响\",\"value\":\"是\",\"abnormal\":true}]";
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.submit((String) r.get("id"), items, null));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("拍照"), "缺图提交校验提示补拍");
    }

    @Test
    void abnormalSubmitCreatesLinkedException() {
        Map<String, Object> r = service.create(Map.of("deviceNo", deviceNo));
        String items = "[{\"item\":\"异响\",\"value\":\"是\",\"abnormal\":true}]";
        Map<String, Object> s = service.submit((String) r.get("id"), items,
                "[\"/uploads/abn-1.jpg\"]");
        assertEquals("1", s.get("abnormalFlag"));
        assertNotNull(s.get("exceptionId"), "异常标记自动生成异常单并回填");
        String excType = jdbc.queryForObject(
                "SELECT EXC_TYPE FROM erp_iot_exception WHERE ID=?",
                String.class, s.get("exceptionId"));
        assertEquals("EQUIPMENT", excType);
    }

    @Test
    void overdueScanMarksFlag() {
        Map<String, Object> r = service.create(Map.of("deviceNo", deviceNo,
                "dueAt", LocalDateTime.now().minusMinutes(10).toString()));
        Map<String, Object> out = service.scanOverdue();
        assertTrue(((Number) out.get("overdueMarked")).intValue() >= 1, "超时漏检标记");
        String flag = jdbc.queryForObject(
                "SELECT OVERDUE_FLAG FROM erp_iot_inspect_record WHERE ID=?",
                String.class, r.get("id"));
        assertEquals("1", flag);
    }
}
