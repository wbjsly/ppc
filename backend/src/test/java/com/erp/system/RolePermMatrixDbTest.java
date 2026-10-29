package com.erp.system;

import com.erp.service.PermMatrixService;
import com.erp.service.SysMenuService;
import com.erp.entity.system.SysMenu;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限矩阵与 override 覆盖层（add-role-permission-console spec：权限矩阵与 override 覆盖层）。
 * 覆盖正/负向写、基线列不变、清除回退、099 重放不丢、最小差异幂等。
 */
@SpringBootTest
class RolePermMatrixDbTest {

    private static final String ROLE_M = "ROLE_RCR_M"; // 基线外角色
    private static final String ROLE_N = "ROLE_RCR_N"; // 基线内角色（菜单 PERM 含它）

    @Autowired
    private PermMatrixService matrix;
    @Autowired
    private SysMenuService menuService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void purge() {
        jdbc.execute("DELETE FROM erp_admin_menu_perm_override WHERE ROLE_CODE LIKE 'ROLE_RCR%'");
        jdbc.execute("DELETE FROM erp_admin_menu WHERE ID LIKE 'rcr-mat-%'");
        jdbc.execute("DELETE FROM erp_admin_role WHERE ROLE_CODE IN ('ROLE_RCR_M','ROLE_RCR_N')");
    }

    private void seedRole(String code) {
        jdbc.update("INSERT INTO erp_admin_role (ID, ROLE_CODE, ROLE_NAME, SORT_ORDER, STATUS, "
                        + "CREATE_BY, DEL_FLAG, VER_NO) VALUES (?,?,?,99, '1', 'rcr-test', '0', 0)",
                "rcr-role-" + code.substring(5).toLowerCase(), code, "矩阵测试角色");
    }

    private void seedMenu(String id, String perm) {
        String menuCode = "16." + (Math.abs(id.hashCode()) % 1000 + 900);
        jdbc.update("INSERT INTO erp_admin_menu (ID, PARENT_ID, MENU_CODE, TITLE, PATH, ICON, "
                        + "SORT_ORDER, PERM, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES (?, 'M16', ?, '矩阵测试菜单', '', '', 98, ?, '1', 'rcr-test', NOW(), '0', 0)",
                id, menuCode, perm);
    }

    private Map<String, Object> cell(String role, String menuId) {
        return matrix.matrixForRole(role).stream()
                .filter(c -> menuId.equals(c.get("id"))).findFirst().orElseThrow();
    }

    private boolean menuVisibleTo(String role, String menuId) {
        List<SysMenu> menus = menuService.getUserMenus(List.of(role));
        return menus.stream().anyMatch(m -> menuId.equals(m.getId()));
    }

    @Test
    void addOverrideGrantsVisibilityWithoutTouchingBaseline() {
        seedRole(ROLE_M);
        seedMenu("rcr-mat-1", "ROLE_SALES"); // 基线不含 ROLE_RCR_M

        Map<String, Object> before = cell(ROLE_M, "rcr-mat-1");
        assertEquals(false, before.get("baseline"));
        assertEquals(false, before.get("effective"));
        assertEquals("BASE", before.get("source"));
        assertFalse(menuVisibleTo(ROLE_M, "rcr-mat-1"));

        matrix.setCell(ROLE_M, "rcr-mat-1", true);

        // 覆盖行落库且为 ADD
        String action = jdbc.queryForObject(
                "SELECT ACTION FROM erp_admin_menu_perm_override "
                        + "WHERE ROLE_CODE=? AND MENU_ID=?", String.class, ROLE_M, "rcr-mat-1");
        assertEquals("ADD", action);
        // 基线列不变
        String perm = jdbc.queryForObject(
                "SELECT PERM FROM erp_admin_menu WHERE ID='rcr-mat-1'", String.class);
        assertEquals("ROLE_SALES", perm, "矩阵写入不得修改基线 PERM 列");
        // 合并后可见
        assertTrue(menuVisibleTo(ROLE_M, "rcr-mat-1"));
        Map<String, Object> after = cell(ROLE_M, "rcr-mat-1");
        assertEquals(true, after.get("effective"));
        assertEquals("OVERRIDE_ADD", after.get("source"));
    }

    @Test
    void removeOverrideHidesBaselineVisibleMenu() {
        seedRole(ROLE_N);
        seedMenu("rcr-mat-2", "ROLE_RCR_N"); // 基线含 ROLE_RCR_N

        assertTrue(menuVisibleTo(ROLE_N, "rcr-mat-2"));
        assertEquals(true, cell(ROLE_N, "rcr-mat-2").get("baseline"));

        matrix.setCell(ROLE_N, "rcr-mat-2", false);

        String action = jdbc.queryForObject(
                "SELECT ACTION FROM erp_admin_menu_perm_override "
                        + "WHERE ROLE_CODE=? AND MENU_ID=?", String.class, ROLE_N, "rcr-mat-2");
        assertEquals("REMOVE", action);
        String perm = jdbc.queryForObject(
                "SELECT PERM FROM erp_admin_menu WHERE ID='rcr-mat-2'", String.class);
        assertEquals("ROLE_RCR_N", perm, "取消勾选不得改基线 PERM 列");
        assertFalse(menuVisibleTo(ROLE_N, "rcr-mat-2"), "REMOVE 覆盖须压过基线");
        Map<String, Object> after = cell(ROLE_N, "rcr-mat-2");
        assertEquals(false, after.get("effective"));
        assertEquals("OVERRIDE_REMOVE", after.get("source"));
    }

    @Test
    void clearRoleRestoresBaseline() {
        seedRole(ROLE_M);
        seedRole(ROLE_N);
        seedMenu("rcr-mat-1", "ROLE_SALES");
        seedMenu("rcr-mat-2", "ROLE_RCR_N");
        matrix.setCell(ROLE_M, "rcr-mat-1", true);
        matrix.setCell(ROLE_N, "rcr-mat-2", false);

        matrix.clearRole(ROLE_M);
        matrix.clearRole(ROLE_N);

        Integer left = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_menu_perm_override "
                        + "WHERE ROLE_CODE IN ('ROLE_RCR_M','ROLE_RCR_N')", Integer.class);
        assertEquals(0, left, "清除覆盖须物理删除全部差异格");
        assertFalse(menuVisibleTo(ROLE_M, "rcr-mat-1"), "回退后基线外角色不可见");
        assertTrue(menuVisibleTo(ROLE_N, "rcr-mat-2"), "回退后基线内角色恢复可见");
        assertEquals("BASE", cell(ROLE_M, "rcr-mat-1").get("source"));
    }

    @Test
    void seedReplayDoesNotLoseOverrides() {
        seedRole(ROLE_M);
        seedMenu("rcr-mat-1", "ROLE_SALES");
        matrix.setCell(ROLE_M, "rcr-mat-1", true);

        // 模拟 099 重放：同值 INSERT IGNORE 重写基线行（生成物只动 erp_admin_menu）
        jdbc.update("INSERT IGNORE INTO erp_admin_menu (ID, PARENT_ID, MENU_CODE, TITLE, PATH, "
                        + "ICON, SORT_ORDER, PERM, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES ('rcr-mat-1','M16','16.991','矩阵测试菜单','','',98,'ROLE_SALES',"
                        + "'1','rcr-test',NOW(),'0',0)");

        Integer ovr = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_menu_perm_override "
                        + "WHERE ROLE_CODE=? AND MENU_ID='rcr-mat-1'", Integer.class, ROLE_M);
        assertEquals(1, ovr, "099 重放不得丢失矩阵改动");
        assertTrue(menuVisibleTo(ROLE_M, "rcr-mat-1"), "重放后覆盖仍生效");
    }

    @Test
    void setCellIsMinimalAndIdempotent() {
        seedRole(ROLE_M);
        seedMenu("rcr-mat-3", "ROLE_SALES");
        seedMenu("rcr-mat-4", "ROLE_RCR_M"); // 基线已含 → 勾选应零写入

        matrix.setCell(ROLE_M, "rcr-mat-3", true);
        matrix.setCell(ROLE_M, "rcr-mat-3", true); // 幂等
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_menu_perm_override WHERE ROLE_CODE=?",
                Integer.class, ROLE_M);
        assertEquals(1, rows, "重复勾选不得叠加行");

        matrix.setCell(ROLE_M, "rcr-mat-4", true); // 基线本就可见 → 最小差异零写入
        rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_menu_perm_override WHERE ROLE_CODE=?",
                Integer.class, ROLE_M);
        assertEquals(1, rows, "勾选基线已含菜单不得产生冗余覆盖行");

        matrix.setCell(ROLE_M, "rcr-mat-3", false); // ADD 格取消 → 删格回基线
        rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_menu_perm_override WHERE ROLE_CODE=?",
                Integer.class, ROLE_M);
        assertEquals(0, rows, "取消勾选 ADD 格须删格恢复基线");
        assertFalse(menuVisibleTo(ROLE_M, "rcr-mat-3"));
    }
}
