package com.erp.intf;

import com.erp.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SLA 月报 HTTP/服务层权限（add-service-monitoring-console，任务 1.3，
 * spec service-monitoring-console「集成架构师审核 HTTP 可达与角色强制」）：
 * ARCH 可查询/生成并进入审核服务层（自审互斥 422 证明非 403）；
 * OPS 审核被服务层 403（角色强制）；归档/发布服务层 ADMIN 二校；
 * 无令牌 401。ServiceException → HTTP 200 + body code（GlobalExceptionHandler）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SlaConsoleApiTest {

    private static final String MONTH = "209901";
    private static final String USER = "sla-console-tester";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM erp_intf_sla_report WHERE MONTH_TAG=?", MONTH);
        jdbc.update("DELETE FROM erp_intf_sla_report WHERE ID LIKE 'slac-r-%'");
        jdbc.update("DELETE FROM erp_intf_channel WHERE PARTNER_CODE LIKE 'SLAC-%'");
        jdbc.update("DELETE FROM erp_admin_user WHERE ID='slac-portal-user'");
    }

    /** 3.4 门户章节级可见（spec：仅已发布 + 仅本人章节 + 无 partner 入参） */
    @Test
    void portalSeesOnlyOwnChaptersAndPublishedOnly() throws Exception {
        // 门户用户（token name = 用户 ID，登录同款 generateToken(user.getId())）绑定供应商 slac-sup
        jdbc.update("INSERT INTO erp_admin_user (ID,USERNAME,PASSWORD,STATUS,SUPPLIER_ID," +
                        "CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO) " +
                        "VALUES ('slac-portal-user','slac-portal','x','1','slac-sup','test',NOW(),'0',0)");
        // 关联通道（本供应商）+ 他供应商通道
        jdbc.update("INSERT INTO erp_intf_channel (ID,PARTNER_CODE,PARTNER_NAME,SUPPLIER_ID," +
                        "PARTNER_TIER,CHANNEL_TYPE,PROTOCOL,STATUS,RATE_TIER,LIMIT_PER_MIN," +
                        "CIRCUIT_STATE,CIRCUIT_PROBE_OK,PROBE_HITS,CIRCUIT_TRIP_COUNT," +
                        "CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO) VALUES " +
                        "('slac-ch-mine','SLAC-CH-MINE','mine','slac-sup','NEW','API','HTTPS'," +
                        "'SANDBOX','NEW',30,'CLOSED',1,0,0,'test',NOW(),'0',0), " +
                        "('slac-ch-other','SLAC-CH-OTHER','other','other-sup','NEW','API','HTTPS'," +
                        "'SANDBOX','NEW',30,'CLOSED',1,0,0,'test',NOW(),'0',0)");
        String metrics = "{\"overview\":{\"P95\":10},\"chapters\":[" +
                "{\"partnerCode\":\"SLAC-CH-MINE\",\"metrics\":{},\"unreach\":[]}," +
                "{\"partnerCode\":\"SLAC-CH-OTHER\",\"metrics\":{},\"unreach\":[]}]}";
        jdbc.update("INSERT INTO erp_intf_sla_report (ID,MONTH_TAG,TITLE,STATUS,VERSION," +
                        "METRICS_JSON,UNREACHED_JSON,GENERATED_BY,GENERATED_AT,PUBLISH_AT," +
                        "PUBLISH_SCOPE,DEL_FLAG,VER_NO) VALUES " +
                        "('slac-r-pub','209903','pub','PUBLISHED','v1',?,'[]','sla-job',NOW()," +
                        "NOW(),'ALL','0',0), " +
                        "('slac-r-draft','209904','draft','GENERATED','v1','{}','[]','sla-job'," +
                        "NOW(),NULL,NULL,'0',0)", metrics);

        String token = jwtTokenProvider.generateToken("slac-portal-user",
                Map.of("roles", List.of("ROLE_SUPPLIER")), 5 * 60 * 1000L);
        // 列表仅已发布（共享库可能存在冒烟族已发布报告 → 断言包含/排除而非总数）
        mockMvc.perform(get("/api/portal/sla-reports").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[*].id",
                        org.hamcrest.Matchers.hasItem("slac-r-pub")))
                .andExpect(jsonPath("$.data[*].id",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("slac-r-draft"))));
        // 详情仅本人章节（带他伙伴参数也只回自己——无 partner 入参，身份取登录态）
        mockMvc.perform(get("/api/portal/sla-reports/slac-r-pub?partnerCode=SLAC-CH-OTHER")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chapters.length()").value(1))
                .andExpect(jsonPath("$.data.chapters[0].partnerCode").value("SLAC-CH-MINE"))
                .andExpect(jsonPath("$.data.overview.P95").value(10));
        // 未发布报告不可见（404）
        mockMvc.perform(get("/api/portal/sla-reports/slac-r-draft")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void unauthenticatedGets401() throws Exception {
        mockMvc.perform(get("/api/intf/sla/reports")).andExpect(status().isUnauthorized());
    }

    @Test
    void archCanQueryGenerateAndReachReview() throws Exception {
        String token = tokenWithRoles("ROLE_INTF_ARCH");
        // 查询可达
        mockMvc.perform(get("/api/intf/sla/reports").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        // 手动生成：记当前操作人为生成人（互斥前置）
        mockMvc.perform(post("/api/intf/sla/reports/generate")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"monthTag\":\"" + MONTH + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        String generatedBy = jdbc.queryForObject(
                "SELECT GENERATED_BY FROM erp_intf_sla_report WHERE MONTH_TAG=?", String.class, MONTH);
        org.junit.jupiter.api.Assertions.assertEquals(USER, generatedBy,
                "手动生成记录实际操作人（spec MODIFIED 月报 req）");
        String id = jdbc.queryForObject(
                "SELECT ID FROM erp_intf_sla_report WHERE MONTH_TAG=?", String.class, MONTH);
        // 审核进服务层：角色通过 → 自审互斥 422（非 HTTP 403）
        mockMvc.perform(post("/api/intf/sla/reports/" + id + "/review")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"opinion\":\"复核通过\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(422));
    }

    @Test
    void opsCannotReviewButCanQuery() throws Exception {
        String token = tokenWithRoles("ROLE_INTF_OPS");
        mockMvc.perform(get("/api/intf/sla/reports").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        // 服务层角色强制：OPS 审核 403（先于 404 查找）
        mockMvc.perform(post("/api/intf/sla/reports/no-such-id/review")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"opinion\":\"复核\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void archiveAndPublishStayAdminOnly() throws Exception {
        // ADMIN 过服务层 requireAdmin → 落到查无此报告 404
        mockMvc.perform(post("/api/intf/sla/reports/no-such-id/archive")
                        .header("Authorization", "Bearer " + tokenWithRoles("ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
        // OPS 过 HTTP 兜底但被服务层 requireAdmin 403
        mockMvc.perform(post("/api/intf/sla/reports/no-such-id/archive")
                        .header("Authorization", "Bearer " + tokenWithRoles("ROLE_INTF_OPS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(post("/api/intf/sla/reports/no-such-id/publish")
                        .header("Authorization", "Bearer " + tokenWithRoles("ROLE_INTF_OPS"))
                        .contentType("application/json").content("{\"scope\":\"ALL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    private String tokenWithRoles(String... roles) {
        return jwtTokenProvider.generateToken(USER,
                Map.of("roles", List.of(roles)), 5 * 60 * 1000L);
    }
}
