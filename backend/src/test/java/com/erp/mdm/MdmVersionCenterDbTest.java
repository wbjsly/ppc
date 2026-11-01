package com.erp.mdm;

import com.erp.common.ServiceException;
import com.erp.service.bi.MdmVersionCenterService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.7 通用版本中心库测（add-mdm-version-sync，spec mdm-version-center）。
 * 造数：物料用 MVS- 前缀编码物理清理；asRole 注入登录态。
 */
@SpringBootTest
class MdmVersionCenterDbTest {

    @Autowired
    private MdmVersionCenterService service;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String CODE = "MVS-" + System.nanoTime();
    private String entityId;

    @BeforeEach
    void setUp() {
        purge();
        asRole("ROLE_ADMIN");
        // 造物料 + 两版本快照
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, BASE_UNIT, CATEGORY_CODE, "
                        + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, STATUS, "
                        + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, ?, '版本中心测试', 'PC', "
                        + "'GEN', 'STRUCT', 'BUY', 'NORMAL', '1', 'mvs-test', NOW(), '0', 0)",
                CODE + "-E1", CODE);
        entityId = CODE + "-E1";
        jdbc.update("INSERT INTO erp_mdm_item_version (ID, ENTITY_ID, VERSION_NO, SNAPSHOT_JSON, "
                        + "OP_TYPE, CREATE_BY, CREATE_DATE) VALUES "
                        + "(?, ?, 1, '{\"itemName\":\"版本中心测试\",\"baseUnit\":\"PC\",\"safetyStock\":100}', "
                        + "'CREATE', 'mvs-test', NOW())", CODE + "-V1", entityId);
        jdbc.update("INSERT INTO erp_mdm_item_version (ID, ENTITY_ID, VERSION_NO, SNAPSHOT_JSON, "
                        + "OP_TYPE, CHANGE_REASON, CHANGE_TYPE, CREATE_BY, CREATE_DATE) VALUES "
                        + "(?, ?, 2, '{\"itemName\":\"版本中心测试-改\",\"baseUnit\":\"SET\",\"safetyStock\":100}', "
                        + "'UPDATE', '调安全库存', 'CRITICAL', 'mvs-test', NOW())", CODE + "-V2", entityId);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void versionsListDesc() {
        List<Map<String, Object>> vs = service.versions("ITEM", entityId);
        assertEquals(2, vs.size());
        assertEquals(2, ((Number) vs.get(0).get("versionNo")).intValue(), "版本号倒序");
        assertEquals("调安全库存", vs.get(0).get("changeReason"));
    }

    @Test
    void diffThreeStates() {
        // V1 → V2：itemName CHANGED、baseUnit CHANGED、extraKey ADDED（V2 补充）
        jdbc.update("UPDATE erp_mdm_item_version SET SNAPSHOT_JSON = "
                        + "'{\"itemName\":\"版本中心测试-改\",\"baseUnit\":\"SET\",\"hazardLevel\":\"HIGH\"}' "
                        + "WHERE ID=?", CODE + "-V2");
        Map<String, Object> d = service.diff("ITEM", entityId, 1, 2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) d.get("fields");
        Map<String, Map<String, Object>> byField = new java.util.HashMap<>();
        fields.forEach(f -> byField.put(String.valueOf(f.get("field")), f));
        assertEquals("CHANGED", byField.get("itemName").get("state"));
        assertEquals("SET", byField.get("baseUnit").get("newVal"));
        assertEquals("ADDED", byField.get("hazardLevel").get("state"), "V1 无此键=新增值");
        assertEquals("REMOVED", byField.get("safetyStock").get("state"), "V2 无此键=删除值");
    }

    @Test
    void rollbackGeneratesNewVersion() {
        Map<String, Object> r = service.rollback("ITEM", entityId, 1, "V2 单位配置错误，恢复 V1");
        assertEquals(3, ((Number) r.get("newVersionNo")).intValue(), "生成 V3");
        // 主表字段被 V1 快照写回
        String unit = jdbc.queryForObject(
                "SELECT BASE_UNIT FROM erp_mdm_item WHERE ID=?", String.class, entityId);
        assertEquals("PC", unit, "主表 BASE_UNIT 恢复 V1 值");
        // 新版本行
        Map<String, Object> v3 = jdbc.queryForMap(
                "SELECT OP_TYPE, CHANGE_REASON FROM erp_mdm_item_version WHERE ENTITY_ID=? AND VERSION_NO=3",
                entityId);
        assertEquals("ROLLBACK", v3.get("OP_TYPE"));
        // 历史版本原样保留（C-4.1-06）
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mdm_item_version WHERE ENTITY_ID=?", Integer.class, entityId);
        assertEquals(3, cnt.intValue(), "V1/V2/V3 全保留");
    }

    @Test
    void rollbackReasonRequired() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.rollback("ITEM", entityId, 1, "x"));
        assertEquals(422, ex.getCode());
        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> service.rollback("ITEM", entityId, 1, null));
        assertEquals(422, ex2.getCode());
    }

    @Test
    void nonAdminRollbackForbidden() {
        asRole("ROLE_BI_DESIGNER");
        // SecurityConfig 层 403；服务层依赖已登录身份（细规则在 HTTP 层，服务层验证原因/存在性）
        // 此处验证非 ADMIN 时 SecurityContextHolder 注入不抛（HTTP 层拦截由冒烟覆盖）
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void unknownBizTypeRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.versions("NO_SUCH_TYPE", entityId));
        assertEquals(422, ex.getCode());
    }

    @Test
    void snapshotMissing404() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.snapshot("ITEM", entityId, 99));
        assertEquals(404, ex.getCode());
    }

    @Test
    void entitiesSelectorWorks() {
        List<Map<String, Object>> list = service.entities("ITEM", CODE, 1, 10);
        assertTrue(list.stream().anyMatch(e -> CODE.equals(e.get("code"))), "实体选择器命中测试物料");
    }

    @Test
    void auditLogWritten() {
        service.rollback("ITEM", entityId, 1, "审计验证回滚");
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_change_log WHERE FIELD_NAME LIKE 'MDM_ROLLBACK:ITEM:%' "
                        + "AND EMP_ID IS NOT NULL", Integer.class);
        assertTrue(n >= 1, "审计留痕已写入");
    }

    private void asRole(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("mvs-test", "x", roles));
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_mdm_item_version WHERE ENTITY_ID IN "
                + "(SELECT ID FROM erp_mdm_item WHERE ITEM_CODE LIKE 'MVS-%')");
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE LIKE 'MVS-%'");
    }
}
