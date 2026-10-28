package com.erp.scm;

import com.erp.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CPFR 操作台权限矩阵（add-cpfr-forecast-replenishment，spec cpfr-collaboration R8）：
 * 查询 = SCMDIR/PMGR/PM/ADMIN；协议启用/停用 = SCMDIR（+ADMIN）；
 * PMGR 可见不可启用（菜单可见但写口 403）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CpfrConsoleApiTest {

    private static final String[] READ_PROBES = {
            "/api/scm/cpfr/agreements",
            "/api/scm/cpfr/forecasts",
            "/api/scm/cpfr/exceptions"
    };

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void unauthenticatedGets401() throws Exception {
        for (String path : READ_PROBES) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/scm/cpfr/agreements/x/enable")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unrelatedRoleGets403() throws Exception {
        String token = tokenWithRoles("ROLE_SALES");
        for (String path : READ_PROBES) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void pmgrCanReadButNotEnable() throws Exception {
        String token = tokenWithRoles("ROLE_PMGR");
        for (String path : READ_PROBES) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
        // 启用限 SCMDIR/ADMIN：PMGR 403（spec R8：PMGR 是审批签署方不碰协议配置）
        mockMvc.perform(post("/api/scm/cpfr/agreements/x/enable")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void pmPassesAuthGate() throws Exception {
        String token = tokenWithRoles("ROLE_PM");
        for (String path : READ_PROBES) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
        // PM 也不能启用协议
        mockMvc.perform(post("/api/scm/cpfr/agreements/x/enable")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void scmdirPassesAuthGateOnAllProbes() throws Exception {
        String token = tokenWithRoles("ROLE_SCMDIR");
        for (String path : READ_PROBES) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
        // 通过鉴权后业务层 404（无此协议）——SCMDIR 启用权限面已过
        mockMvc.perform(post("/api/scm/cpfr/agreements/no-such/enable")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void adminPassesAuthGateOnAllProbes() throws Exception {
        String token = tokenWithRoles("ROLE_ADMIN");
        for (String path : READ_PROBES) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/scm/cpfr/agreements/no-such/enable")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    private String tokenWithRoles(String... roles) {
        return jwtTokenProvider.generateToken("cpfr-api-tester",
                Map.of("roles", List.of(roles)), 5 * 60 * 1000L);
    }
}
