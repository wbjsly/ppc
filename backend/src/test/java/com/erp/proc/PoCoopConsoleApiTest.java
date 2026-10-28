package com.erp.proc;

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
 * PO 协同操作台权限矩阵（add-po-collaboration-console 任务 2.6，spec po-collaboration 增补）：
 * 读（工单/台账分页）= ADMIN/PM/WAREHOUSE；协商/确认类 = ADMIN/PM；
 * 工单 resolve 与 unlocks = ADMIN 专属（SecurityConfig 细规则先于通用 POST）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PoCoopConsoleApiTest {

    private static final String[] READ_PROBES = {
            "/api/proc/po-coops/tickets",
            "/api/proc/po-coops/push-ledger"
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
        mockMvc.perform(post("/api/proc/po-coops/tickets/x/resolve")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"r\"}"))
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
    void warehouseCanReadButNotResolve() throws Exception {
        String token = tokenWithRoles("ROLE_WAREHOUSE");
        for (String path : READ_PROBES) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
        // resolve 限 ADMIN：WAREHOUSE 403
        mockMvc.perform(post("/api/proc/po-coops/tickets/x/resolve")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"r\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void pmCanNegotiateButNotResolve() throws Exception {
        String token = tokenWithRoles("ROLE_PM");
        // 协商放行 ADMIN+PM（HTTP 200 + body code=404 业务层查无此 PO，鉴权已过）
        mockMvc.perform(post("/api/proc/po-coops/no-such-po/negotiate")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"协商说明\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
        // unlocks 与 resolve 均限 ADMIN
        mockMvc.perform(post("/api/proc/po-coops/unlocks")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"supplierId\":\"s\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/proc/po-coops/tickets/x/resolve")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"r\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminPassesAuthGateOnAllProbes() throws Exception {
        String token = tokenWithRoles("ROLE_ADMIN");
        for (String path : READ_PROBES) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
        // 通过鉴权后业务层 404（无此工单/供应商未锁定）——权限面已过
        mockMvc.perform(post("/api/proc/po-coops/tickets/no-such/resolve")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"继续合作\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(post("/api/proc/po-coops/no-such-po/negotiate")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"协商\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    private String tokenWithRoles(String... roles) {
        return jwtTokenProvider.generateToken("pcc-api-tester",
                Map.of("roles", List.of(roles)), 5 * 60 * 1000L);
    }
}
