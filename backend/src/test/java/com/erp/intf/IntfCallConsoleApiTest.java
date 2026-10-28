package com.erp.intf;

import com.erp.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 12.3 操作台 HTTP 层权限（add-interface-call-console，任务 3.6，spec 12.3.2 菜单路由与权限）：
 * 新端点落 SecurityConfig `/api/intf/**` 兜底（ADMIN/INTF_OPS）——
 * 无令牌 401、非白名单角色 403、INTF_OPS 可读 200。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IntfCallConsoleApiTest {

    private static final String[] PROBES = {
            "/api/intf/call-console/auth-rejections",
            "/api/intf/call-console/rate-overview",
            "/api/intf/call-console/tier-changes",
            "/api/intf/call-console/rate-trend",
            "/api/intf/call-console/trace/any-req-id",
            "/api/intf/call-console/audit-aggregates",
            "/api/intf/call-console/credentials"
    };

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void unauthenticatedGets401() throws Exception {
        for (String path : PROBES) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void nonWhitelistRoleGets403() throws Exception {
        String token = tokenWithRoles("ROLE_WAREHOUSE");
        for (String path : PROBES) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void intfOpsCanReadAllProbes() throws Exception {
        String token = tokenWithRoles("ROLE_INTF_OPS");
        for (String path : PROBES) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
    }

    private String tokenWithRoles(String... roles) {
        return jwtTokenProvider.generateToken("perm-tester",
                Map.of("roles", List.of(roles)), 5 * 60 * 1000L);
    }
}
