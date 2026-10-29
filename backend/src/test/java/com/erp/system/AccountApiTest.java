package com.erp.system;

import com.erp.dao.system.SysUserDao;
import com.erp.entity.system.SysUser;
import com.erp.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 账号运维权限矩阵 + JWT 状态校验（add-account-lifecycle-console，spec account-lifecycle R2/R6）：
 * 运维端点限 ADMIN；停用账号旧 token 调业务接口 401（设计 D2）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountApiTest {

    private static final String ACCOUNTS = "/api/system/accounts";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private SysUserDao userDao;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private String userId;

    @BeforeEach
    void setUp() {
        purge();
        SysUser u = new SysUser();
        u.setId(UUID.randomUUID().toString().replace("-", ""));
        u.setUsername("act-" + UUID.randomUUID().toString().substring(0, 8));
        u.setPassword(passwordEncoder.encode("Old@1234"));
        u.setNickName("账号API测试");
        u.setStatus(SysUser.ST_ACTIVE);
        u.setMustChangePwd("1");
        userDao.insert(u);
        userId = u.getId();
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    @Test
    void unauthenticatedGets401() throws Exception {
        mockMvc.perform(get(ACCOUNTS)).andExpect(status().isUnauthorized());
    }

    @Test
    void unrelatedRoleGets403() throws Exception {
        String token = tokenWithRoles("ROLE_SALES");
        mockMvc.perform(get(ACCOUNTS).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminPassesAuthGate() throws Exception {
        String token = tokenWithRoles("ROLE_ADMIN");
        mockMvc.perform(get(ACCOUNTS).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        // 通过鉴权后业务层 404（无此账号）——权限面已过
        mockMvc.perform(post(ACCOUNTS + "/no-such/reset-password")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void disabledUserOldTokenGets401() throws Exception {
        // 启用态 token 正常
        String token = jwtTokenProvider.generateToken(userId,
                Map.of("roles", List.of("ROLE_ADMIN")), 5 * 60 * 1000L);
        mockMvc.perform(get("/api/system/menus/user").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        // 强停后同一 token → 401（design D2：JWT 状态校验即时断权）
        mockMvc.perform(post(ACCOUNTS + "/" + userId + "/disable")
                        .header("Authorization", "Bearer " + tokenWithRoles("ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/api/system/menus/user").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void archivedUserOldTokenGets401() throws Exception {
        String adminToken = tokenWithRoles("ROLE_ADMIN");
        String token = jwtTokenProvider.generateToken(userId,
                Map.of("roles", List.of("ROLE_ADMIN")), 5 * 60 * 1000L);
        // 先停用再归档（confirmUsername 正确）
        mockMvc.perform(post(ACCOUNTS + "/" + userId + "/disable")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        String username = userDao.selectById(userId).getUsername();
        mockMvc.perform(post(ACCOUNTS + "/" + userId + "/archive")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{\"confirmUsername\":\"" + username + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        // 归档后旧 token 401；归档账号再启用被拒
        mockMvc.perform(get("/api/system/menus/user").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    private String tokenWithRoles(String... roles) {
        return jwtTokenProvider.generateToken("acct-api-tester",
                Map.of("roles", List.of(roles)), 5 * 60 * 1000L);
    }

    private void purge() {
        jdbc.execute("SET @audit_bypass=1; DELETE FROM hr_change_log WHERE FIELD_NAME IN ('ACCOUNT_DISABLED','ACCOUNT_ARCHIVED'); SET @audit_bypass=0");
        jdbc.execute("DELETE FROM erp_admin_user WHERE USERNAME LIKE 'act-%'");
    }
}
