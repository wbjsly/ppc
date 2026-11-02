package com.erp.iot;

import com.erp.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 13 域 SecurityConfig 细规则测试（add-iot-mobile，任务 1.4）：
 * 未认证 401、无角色 403、IOT_ADMIN 200、现场角色可执行。
 * 走真实 springSecurityFilterChain（MockMvc），非 Mock 掉 Security。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IotSecurityRuleTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private String token(List<String> roles) {
        return jwtTokenProvider.generateToken("user-admin", Map.of("roles", roles));
    }

    @Test
    void unauthenticatedGets401() throws Exception {
        mockMvc.perform(post("/api/iot/terminals/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceCode\":\"sec-unauth\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/iot/terminals"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void noRoleGets403() throws Exception {
        // 仅普通用户角色 → 管理口 403
        mockMvc.perform(post("/api/iot/terminals/register")
                        .header("Authorization", "Bearer " + token(List.of("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceCode\":\"sec-norole-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void iotAdminCanRegister() throws Exception {
        String code = "sec-admin-" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/api/iot/terminals/register")
                        .header("Authorization", "Bearer " + token(List.of("ROLE_IOT_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceCode\":\"" + code + "\",\"deviceType\":\"PDA\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/iot/terminals/" + code)
                        .header("Authorization", "Bearer " + token(List.of("ROLE_IOT_ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void shopFloorCanExecuteButNotAdmin() throws Exception {
        String tok = token(List.of("ROLE_SHOP_FLOOR"));
        // 现场角色：执行口（离线同步上传）不被安全层拦截（业务层/404 均可，仅排除 401/403）
        mockMvc.perform(post("/api/iot/sync/upload")
                        .header("Authorization", "Bearer " + tok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(result -> {
                    int sc = result.getResponse().getStatus();
                    org.junit.jupiter.api.Assertions.assertFalse(sc == 401 || sc == 403,
                            "现场角色不应被 401/403 拦截，实际 " + sc);
                });
        // 现场角色：终端注册管理口 403
        mockMvc.perform(post("/api/iot/terminals/register")
                        .header("Authorization", "Bearer " + tok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceCode\":\"sec-shop-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden());
    }
}
