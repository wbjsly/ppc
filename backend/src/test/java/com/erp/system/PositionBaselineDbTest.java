package com.erp.system;

import com.erp.common.ServiceException;
import com.erp.service.hr.HrOrgService;
import com.erp.service.system.PositionBaselineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 岗位基线（add-role-permission-console spec：岗位基线与特权清单硬阻断）。
 * 物理清理：基线按 POS_ID 前缀 rpb- / 关联测试岗位名清理（唯一键不区分逻辑删除）。
 */
@SpringBootTest
class PositionBaselineDbTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PositionBaselineService baselineService;
    @Autowired
    private HrOrgService hrOrgService;

    @BeforeEach
    @AfterEach
    void purge() {
        jdbc.execute("DELETE FROM erp_admin_position_baseline WHERE POS_ID LIKE 'rpb-%' "
                + "OR POS_ID IN (SELECT ID FROM hr_position WHERE NAME LIKE 'RPB测试%')");
        jdbc.execute("DELETE FROM hr_position WHERE NAME LIKE 'RPB测试%'");
        jdbc.execute("DELETE FROM hr_dept WHERE NAME LIKE 'RPB测试部门%' AND ID NOT IN "
                + "(SELECT DEPT_ID FROM hr_position WHERE DEPT_ID IS NOT NULL)");
    }

    @Test
    void privilegedBaselineBlockedL1() {
        // 含超管 → 422
        ServiceException e = assertThrows(ServiceException.class,
                () -> baselineService.upsert("rpb-l1", "[\"ROLE_ADMIN\"]", "rpb-test"));
        assertTrue(e.getMessage().contains("ROLE_ADMIN"), e.getMessage());
        // 含审批节点角色 → 422
        e = assertThrows(ServiceException.class,
                () -> baselineService.upsert("rpb-l1", "[\"ROLE_PM\"]", "rpb-test"));
        assertTrue(e.getMessage().contains("ROLE_PM"), e.getMessage());
        // 阻断不落库
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_position_baseline WHERE POS_ID='rpb-l1'",
                Integer.class);
        assertEquals(0, cnt, "L1 阻断后不得留下基线行");
    }

    @Test
    void upsertReadAndOverwrite() {
        baselineService.upsert("rpb-rw", "[\"ROLE_PLANNER\",\"ROLE_USER\"]", "rpb-test");
        List<String> roles = baselineService.rolesOfPos("rpb-rw");
        assertEquals(2, roles.size());
        assertTrue(roles.contains("ROLE_PLANNER"));
        assertNotNull(baselineService.get("rpb-rw"));

        // 再次保存为覆盖（update 而非重复 insert）
        baselineService.upsert("rpb-rw", "[\"ROLE_PLANNER\"]", "rpb-test");
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_position_baseline WHERE POS_ID='rpb-rw'",
                Integer.class);
        assertEquals(1, cnt, "upsert 不得产生第二行");
        assertEquals(1, baselineService.rolesOfPos("rpb-rw").size());

        // 无基线岗位 → 空集合
        assertTrue(baselineService.rolesOfPos("rpb-nonexistent").isEmpty());
        assertTrue(baselineService.rolesOfPos(null).isEmpty());
    }

    @Test
    void positionCreateSyncsBaselineAndL1Blocks() {
        Map<String, Object> deptPayload = new HashMap<>();
        deptPayload.put("name", "RPB测试部门-" + UUID.randomUUID().toString().substring(0, 6));
        var dept = hrOrgService.createDept(deptPayload);

        // 带非特权角色创建岗位 → 基线同步出现
        Map<String, Object> p1 = new HashMap<>();
        p1.put("name", "RPB测试岗位A");
        p1.put("deptId", dept.getId());
        p1.put("rolesJson", "[\"ROLE_PLANNER\"]");
        var pos = hrOrgService.createPosition(p1);
        assertEquals(List.of("ROLE_PLANNER"), baselineService.rolesOfPos(pos.getId()),
                "岗位创建须同步 upsert 基线");

        // 带特权角色创建岗位 → 422 且整单回滚（岗位也不落库）
        Map<String, Object> p2 = new HashMap<>();
        p2.put("name", "RPB测试岗位B");
        p2.put("deptId", dept.getId());
        p2.put("rolesJson", "[\"ROLE_HR\"]");
        ServiceException e = assertThrows(ServiceException.class,
                () -> hrOrgService.createPosition(p2));
        assertTrue(e.getMessage().contains("ROLE_HR"), e.getMessage());
        Integer posCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_position WHERE NAME='RPB测试岗位B'", Integer.class);
        assertEquals(0, posCnt, "L1 阻断须回滚岗位创建");
    }

    @Test
    void stockPlaceholderStripped() {
        // 迁移 186 后：存量占位基线（原 ["ROLE_HR"]）全部被剥离
        Integer withHr = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_position_baseline "
                        + "WHERE ROLE_CODES LIKE '%ROLE_HR%'", Integer.class);
        assertEquals(0, withHr, "存量基线不得残留 ROLE_HR");

        // 取一个存量岗位（原 ROLES_JSON 含 ROLE_HR）→ 读基线为空集合（建号零角色）
        List<String> stockPos = jdbc.queryForList(
                "SELECT ID FROM hr_position WHERE ROLES_JSON LIKE '%ROLE_HR%' "
                        + "AND DEL_FLAG='0' LIMIT 1", String.class);
        if (!stockPos.isEmpty()) {
            assertTrue(baselineService.rolesOfPos(stockPos.get(0)).isEmpty(),
                    "存量岗位基线须为空（特权已剥离）");
        }
    }
}
