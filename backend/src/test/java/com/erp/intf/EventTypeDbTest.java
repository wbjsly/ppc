package com.erp.intf;

import com.erp.common.ServiceException;
import com.erp.entity.intf.IntfEventType;
import com.erp.service.intf.EventTypeService;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件定义注册表 DbTest（add-event-subscription，design D7，spec event-subscription 1.1~1.2）：
 * 命名 422 / 重复 409 / 必填缺失拒发 / 类型不符放行 / 未注册拒发 / 停用拒发 /
 * Schema 未升 Major 422 / 版本落信封 / INTF_OPS 写 403。造数前缀 ETTEST。
 */
@SpringBootTest
class EventTypeDbTest {

    @Autowired
    private EventTypeService service;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String TYPE = "ETTEST_CREATED";

    @BeforeEach
    void setUp() {
        purge();
        asArch();
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    @Test
    void createRejectsBadNaming() {
        ServiceException e = assertThrows(ServiceException.class, () -> service.create(Map.of(
                "eventType", "createDelivery", "typeName", "坏命名")));
        assertEquals(422, e.getCode(), "无 {对象}_{动作} 分段被阻断");
        assertTrue(e.getMessage().contains("{业务对象}_{动作}"));
    }

    @Test
    void createRejectsDuplicateType() {
        service.create(newType(TYPE));
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.create(newType(TYPE)));
        assertEquals(409, e.getCode());
    }

    @Test
    void publishRejectsUnregisteredType() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.validateForPublish("PROC.NOT_REGISTERED", Map.of("x", 1)));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("12.2.1"), "提示先注册");
    }

    @Test
    void publishRejectsMissingRequiredField() {
        service.create(Map.of("eventType", TYPE, "typeName", "测试类型",
                "schemaJson", "{\"required\":[{\"field\":\"poNo\",\"type\":\"string\"}]}"));
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.validateForPublish(TYPE, Map.of("other", "v")));
        assertEquals(422, e.getCode(), "必填缺失 L3 拒发（FR-4.9-2-2）");
        assertTrue(e.getMessage().contains("poNo"));
    }

    @Test
    void publishPassesTypeMismatchWithWarning() {
        service.create(Map.of("eventType", TYPE, "typeName", "测试类型",
                "schemaJson", "{\"required\":[{\"field\":\"poNo\",\"type\":\"string\"}]}"));
        EventTypeService.ValidateResult r = service.validateForPublish(TYPE, Map.of("poNo", 123));
        assertNotNull(r.version());
        assertFalse(r.warnings().isEmpty(), "类型不符 L4 警告放行");
    }

    @Test
    void publishReturnsEnvelopeVersion() {
        service.create(Map.of("eventType", TYPE, "typeName", "测试类型",
                "schemaJson", "{\"required\":[{\"field\":\"poNo\",\"type\":\"string\"}]}"));
        EventTypeService.ValidateResult r = service.validateForPublish(TYPE, Map.of("poNo", "PO1"));
        assertEquals("1.0", r.version(), "信封版本取注册表 Major.Minor");
    }

    @Test
    void publishRejectsDisabledType() {
        Map<String, Object> out = service.create(newType(TYPE));
        service.toggle(String.valueOf(out.get("id")), "DISABLED");
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.validateForPublish(TYPE, Map.of()));
        assertEquals(422, e.getCode(), "停用阻断新事件发布");
    }

    @Test
    void schemaChangeWithoutMajorBumpRejected() {
        Map<String, Object> out = service.create(Map.of("eventType", TYPE, "typeName", "测试类型",
                "schemaJson", "{\"required\":[{\"field\":\"poNo\",\"type\":\"string\"}]}"));
        String id = String.valueOf(out.get("id"));
        // 删必填字段但版本不动 → 422
        ServiceException e = assertThrows(ServiceException.class, () -> service.update(id, Map.of(
                "schemaJson", "{\"required\":[{\"field\":\"asnNo\",\"type\":\"string\"}]}",
                "versionMajor", 1, "versionMinor", 0)));
        assertEquals(422, e.getCode());
        // 升 Major 后放行
        Map<String, Object> ok = service.update(id, Map.of(
                "schemaJson", "{\"required\":[{\"field\":\"asnNo\",\"type\":\"string\"}]}",
                "versionMajor", 2, "versionMinor", 0));
        assertEquals("2.0", ok.get("version"));
        EventTypeService.ValidateResult r = service.validateForPublish(TYPE, Map.of("asnNo", "A1"));
        assertEquals("2.0", r.version());
    }

    @Test
    void intfOpsWriteRejected() {
        asRole("ROLE_INTF_OPS");
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.create(newType(TYPE)));
        assertEquals(403, e.getCode(), "运维只读（spec 1.1 场景）");
    }

    @Test
    void seedsExistForSixLegacyEvents() {
        List<String> types = jdbc.queryForList(
                "SELECT EVENT_TYPE FROM erp_intf_event_type WHERE STATUS='ENABLED' AND DEL_FLAG='0'",
                String.class);
        for (String t : List.of("PROC.PO_PUSHED", "PROC.PO_CONFIRMED", "PROC.ASN_CREATED",
                "VMI.WATER_SYNCED", "VMI.REPLENISH_PUSHED", "VMI.SETTLE_PUSHED")) {
            assertTrue(types.contains(t), "6 类既有事件种子迁入：" + t);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> newType(String type) {
        return Map.of("eventType", type, "typeName", "测试类型",
                "schemaJson", "{\"required\":[{\"field\":\"poNo\",\"type\":\"string\"}]}");
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_intf_event_type WHERE EVENT_TYPE LIKE 'ETTEST%'");
    }

    private void asArch() {
        asRole("ROLE_INTF_ARCH");
    }

    private void asRole(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "et-tester", "x", roles));
    }
}
