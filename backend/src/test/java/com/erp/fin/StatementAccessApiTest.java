package com.erp.fin;

import com.erp.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 供应商对账 HTTP 层权限（add-reconciliation-console，任务 1.2，
 * spec reconciliation-console「财务双签与只读查询 HTTP 可达」）：
 * FINANCE_MGR 读 + confirm 放行（此前被 /api/fin/** 兜底 403 拦死，R2 财务节点不可达）；
 * 登记与 match 保持兜底 ADMIN,PM 口径不被放宽；无令牌 401。
 * confirm 探测用不存在 id：鉴权通过后服务层 require(id) → ServiceException(404)
 * → HTTP 200 + body code 404（GlobalExceptionHandler 语义），据此区分"过鉴权"与 403。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatementAccessApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void unauthenticatedGets401() throws Exception {
        mockMvc.perform(get("/api/fin/statements")).andExpect(status().isUnauthorized());
    }

    @Test
    void financeMgrCanReadListAndReachConfirm() throws Exception {
        String token = tokenWithRoles("ROLE_FINANCE_MGR");
        // 读：差异清单上下文（12.6.2 双签决策）
        mockMvc.perform(get("/api/fin/statements").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        // confirm：过 HTTP 鉴权 → 服务层 404 body（非 403）
        mockMvc.perform(post("/api/fin/statements/no-such-id/confirm")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void financeMgrStillBlockedOnRegisterAndMatch() throws Exception {
        String token = tokenWithRoles("ROLE_FINANCE_MGR");
        mockMvc.perform(post("/api/fin/statements")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/fin/statements/no-such-id/match")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void pmRetainsFullAccess() throws Exception {
        String token = tokenWithRoles("ROLE_PM");
        mockMvc.perform(get("/api/fin/statements").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/fin/statements/no-such-id/confirm")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(post("/api/fin/statements/no-such-id/match")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void warehouseBlockedOnStatements() throws Exception {
        String token = tokenWithRoles("ROLE_WAREHOUSE");
        mockMvc.perform(get("/api/fin/statements").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    private String tokenWithRoles(String... roles) {
        return jwtTokenProvider.generateToken("stmt-access-tester",
                Map.of("roles", List.of(roles)), 5 * 60 * 1000L);
    }
}
