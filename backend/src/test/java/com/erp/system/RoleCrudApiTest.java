package com.erp.system;

import com.erp.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 角色 CRUD 与删除护栏（add-role-permission-console spec：角色全生命周期管理与删除护栏）。
 * 造数统一 ROLE_RCR_* 前缀，物理清理（唯一键不区分逻辑删除）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RoleCrudApiTest {

    private static final String ROLES = "/api/system/roles";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private JdbcTemplate jdbc;

    private String adminToken;

    @BeforeEach
    void setUp() {
        purge();
        adminToken = jwtTokenProvider.generateToken("rcr-admin",
                Map.of("roles", List.of("ROLE_ADMIN")), 5 * 60 * 1000L);
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    private void purge() {
        jdbc.execute("DELETE FROM erp_admin_user_role WHERE ROLE_ID IN "
                + "(SELECT ID FROM erp_admin_role WHERE ROLE_CODE LIKE 'ROLE_RCR%')");
        jdbc.execute("DELETE FROM erp_admin_menu_perm_override WHERE ROLE_CODE LIKE 'ROLE_RCR%'");
        jdbc.execute("DELETE FROM erp_admin_menu WHERE ID LIKE 'rcr-menu-%'");
        jdbc.execute("DELETE FROM erp_admin_role WHERE ROLE_CODE LIKE 'ROLE_RCR%'");
    }

    private String createRole(String code) throws Exception {
        mockMvc.perform(post(ROLES).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"" + code + "\",\"roleName\":\"护栏测试角色\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        return jdbc.queryForObject(
                "SELECT ID FROM erp_admin_role WHERE ROLE_CODE = ?", String.class, code);
    }

    @Test
    void createDuplicateAndPage() throws Exception {
        String id = createRole("ROLE_RCR_A");
        // 重复编码 → 422（含已删行占位判重）
        mockMvc.perform(post(ROLES).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"ROLE_RCR_A\",\"roleName\":\"重复\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(422));
        // 非法编码 → 422
        mockMvc.perform(post(ROLES).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"bad code\",\"roleName\":\"非法\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(422));
        // 分页可查到
        mockMvc.perform(get(ROLES + "/page").param("keyword", "ROLE_RCR_A")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].roleCode").value("ROLE_RCR_A"));
        // 新角色初始无可见性：矩阵未授予前不在任何菜单 PERM 中（矩阵端点 3.2 就绪前以基线断言）
        Integer permHits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_menu WHERE PERM LIKE '%ROLE_RCR_A%'",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, permHits, "新角色不得自带菜单可见性");
        org.junit.jupiter.api.Assertions.assertNotNull(id);
    }

    @Test
    void deleteBlockedByUserBinding() throws Exception {
        String roleId = createRole("ROLE_RCR_B");
        jdbc.update("INSERT INTO erp_admin_user_role (ID, USER_ID, ROLE_ID) VALUES (?,?,?)",
                "rcr-ur-" + UUID.randomUUID().toString().substring(0, 8),
                "rcr-ghost-user", roleId);
        mockMvc.perform(delete(ROLES + "/" + roleId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("用户绑定")));
    }

    @Test
    void deleteBlockedByMenuPermBaseline() throws Exception {
        String roleId = createRole("ROLE_RCR_C");
        jdbc.update("INSERT INTO erp_admin_menu (ID, PARENT_ID, MENU_CODE, TITLE, PATH, ICON, "
                        + "SORT_ORDER, PERM, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES ('rcr-menu-1','M16','16.99','护栏测试菜单','','',99,?,'1',"
                        + "'rcr-test',NOW(),'0',0)", "ROLE_RCR_C");
        mockMvc.perform(delete(ROLES + "/" + roleId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("PERM")));
    }

    @Test
    void deleteBlockedByOverride() throws Exception {
        String roleId = createRole("ROLE_RCR_D");
        jdbc.update("INSERT INTO erp_admin_menu_perm_override (ID, MENU_ID, ROLE_CODE, ACTION, "
                        + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?,?,?,?,?,NOW(),'0',0)",
                "rcr-ovr-" + UUID.randomUUID().toString().substring(0, 8),
                "M16-1", "ROLE_RCR_D", "ADD", "rcr-test");
        mockMvc.perform(delete(ROLES + "/" + roleId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("覆盖")));
    }

    @Test
    void deleteUnreferencedSucceeds() throws Exception {
        String roleId = createRole("ROLE_RCR_E");
        mockMvc.perform(delete(ROLES + "/" + roleId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get(ROLES + "/page").param("keyword", "ROLE_RCR_E")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void superRoleCannotBeDisabledOrDeleted() throws Exception {
        String adminRoleId = jdbc.queryForObject(
                "SELECT ID FROM erp_admin_role WHERE ROLE_CODE='ROLE_ADMIN'", String.class);
        mockMvc.perform(delete(ROLES + "/" + adminRoleId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(422));
        mockMvc.perform(put(ROLES + "/" + adminRoleId).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"0\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(422));
    }
}
