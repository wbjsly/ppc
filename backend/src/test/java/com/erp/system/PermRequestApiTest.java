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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 权限申请 API（add-permission-approval-flow spec：申请单提交与校验 / 预检沙盘 / 详情）。
 * 造数统一 prq- 前缀，物理清理。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PermRequestApiTest {

    private static final String REQ = "/api/system/perm-requests";
    private static final String SOD = "/api/system/sod-rules";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private JdbcTemplate jdbc;

    private String adminToken;
    private String targetUser;

    @BeforeEach
    void setUp() {
        purge();
        jdbc.execute("DELETE FROM erp_change_window WHERE CREATE_BY = 'prq-test'");
        adminToken = jwtTokenProvider.generateToken("prq-admin",
                Map.of("roles", List.of("ROLE_ADMIN")), 5 * 60 * 1000L);
        targetUser = "prq-u" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,"
                        + "DEL_FLAG,VER_NO) VALUES (?,?,?,?,'1','prq-test','0',0)",
                targetUser, targetUser, "{noop}", "申请测试用户");
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    private void purge() {
        jdbc.execute("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i "
                + "ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE='PermRequest' AND i.BIZ_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_perm_request WHERE TARGET_USER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'prq-u%') u)) r)");
        jdbc.execute("DELETE i FROM erp_sys_approval i WHERE i.BIZ_TYPE='PermRequest' "
                + "AND i.BIZ_ID IN (SELECT ID FROM (SELECT ID FROM erp_admin_perm_request "
                + "WHERE TARGET_USER_ID IN (SELECT ID FROM (SELECT ID FROM erp_admin_user "
                + "WHERE USERNAME LIKE 'prq-u%') u)) r)");
        jdbc.execute("DELETE FROM erp_admin_perm_request WHERE TARGET_USER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'prq-u%') t)");
        jdbc.execute("DELETE FROM erp_admin_user WHERE USERNAME LIKE 'prq-u%'");
    }

    private String submitBody(String roles, String scope, String reason, String ticket,
                              String months, String mode) {
        return "{\"targetUserId\":\"" + targetUser + "\",\"roleCodes\":" + roles
                + ",\"dataScope\":" + (scope == null ? "null" : "\"" + scope + "\"")
                + ",\"reason\":" + (reason == null ? "null" : "\"" + reason + "\"")
                + ",\"ticketNo\":" + (ticket == null ? "null" : "\"" + ticket + "\"")
                + ",\"validMonths\":" + (months == null ? "null" : months)
                + ",\"activationMode\":" + (mode == null ? "null" : "\"" + mode + "\"") + "}";
    }

    @Test
    void validationFourBlocks() throws Exception {
        // 理由过短
        mockMvc.perform(post(REQ).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("[\"ROLE_PLANNER\"]", "华东/OU-01", "申请", null, "12", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(422));
        // 数据范围缺失
        mockMvc.perform(post(REQ).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("[\"ROLE_PLANNER\"]", null, "足够长度的业务申请理由", null, "12", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(422));
        // 有效期超 12 月
        mockMvc.perform(post(REQ).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("[\"ROLE_PLANNER\"]", "华东/OU-01", "足够长度的业务申请理由", null, "18", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(422));
        // 特权缺工单号
        mockMvc.perform(post(REQ).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("[\"ROLE_ADMIN\"]", "华东/OU-01", "足够长度的业务申请理由", null, "12", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(422));
    }

    @Test
    void riskLevelAnnotatedAndSubmitSucceeds() throws Exception {
        // 敏感 → SENSITIVE
        mockMvc.perform(post(REQ).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("[\"ROLE_COST_ACCOUNTANT\"]", "华东/OU-01",
                                "成本数据核对需要该权限", null, "6", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.riskLevel").value("SENSITIVE"))
                .andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.data.approvalInstanceId").isNotEmpty());

        // 同用户已有 PENDING → 422（重复提交）
        mockMvc.perform(post(REQ).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("[\"ROLE_PLANNER\"]", "华东/OU-01",
                                "再次提交应当被拒绝的申请", null, "12", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(422));

        // 特权带工单 → PRIVILEGED（清掉前一单的 PENDING 占用）
        jdbc.execute("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i "
                + "ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE='PermRequest' AND i.BIZ_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_perm_request WHERE TARGET_USER_ID='"
                + targetUser + "') r)");
        jdbc.execute("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='PermRequest' AND BIZ_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_perm_request WHERE TARGET_USER_ID='"
                + targetUser + "') r)");
        jdbc.execute("DELETE FROM erp_admin_perm_request WHERE TARGET_USER_ID='" + targetUser + "'");
        mockMvc.perform(post(REQ).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("[\"ROLE_ADMIN\"]", "华东/OU-01",
                                "紧急故障处置需要超管权限", "INC-2026-1029-0001", "1", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.riskLevel").value("PRIVILEGED"));
    }

    @Test
    void precheckSandboxEndpoint() throws Exception {
        mockMvc.perform(post(SOD + "/precheck").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCodes\":[\"ROLE_PURCHASER\",\"ROLE_PM\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.conflicts.length()").value(1))
                .andExpect(jsonPath("$.data.beyondBaseline").isArray());
    }

    @Test
    void detailContainsSnapshotAndApprovalLogs() throws Exception {
        String body = mockMvc.perform(post(REQ).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("[\"ROLE_PLANNER\"]", "华东/OU-01",
                                "详情查看需要的正常申请", null, "12", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).path("data").path("id").asText();

        mockMvc.perform(get(REQ + "/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sodSnapshot").isNotEmpty())
                .andExpect(jsonPath("$.data.approvalLogs.tasks").isArray())
                .andExpect(jsonPath("$.data.request.riskLevel").value("NORMAL"));
    }

    // ---------- add-change-window-console：窗口外 CCB 升级（spec：窗口外普通申请追加 CCB） ----------

    /** 清空本测试种子的窗口（其余测试不得依赖真实时间窗内，全部自控） */
    private void clearWindows() {
        jdbc.execute("DELETE FROM erp_change_window WHERE CREATE_BY = 'prq-test'");
    }

    /** 全周全天窗口（判定恒为窗口内） */
    private void seedAlwaysWindow() {
        for (int d = 1; d <= 7; d++) {
            jdbc.update("INSERT INTO erp_change_window (ID,NAME,DAY_OF_WEEK,START_TIME,END_TIME,"
                            + "STATUS,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO) VALUES "
                            + "(?,? ,?, '00:00', '23:59:59', '1', 'prq-test', NOW(), '0', 0)",
                    "prq-win-" + d, "全周窗口-" + d, d);
        }
    }

    private void purgeRequests() {
        jdbc.execute("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i "
                + "ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE='PermRequest' AND i.BIZ_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_perm_request WHERE TARGET_USER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'prq-u%') u)) r)");
        jdbc.execute("DELETE i FROM erp_sys_approval i WHERE i.BIZ_TYPE='PermRequest' "
                + "AND i.BIZ_ID IN (SELECT ID FROM (SELECT ID FROM erp_admin_perm_request "
                + "WHERE TARGET_USER_ID IN (SELECT ID FROM (SELECT ID FROM erp_admin_user "
                + "WHERE USERNAME LIKE 'prq-u%') u)) r)");
        jdbc.execute("DELETE FROM erp_admin_perm_request WHERE TARGET_USER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'prq-u%') t)");
    }

    private long ccbNodeCount(String reqId) {
        Long cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_approval_task t JOIN erp_sys_approval i "
                        + "ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE='PermRequest' AND i.BIZ_ID=? "
                        + "AND t.ROLE_REQUIRED IN ('ROLE_CCB_OPS','ROLE_CCB_ARCH','ROLE_CCB_BIZ')",
                Long.class, reqId);
        return cnt == null ? 0 : cnt;
    }

    @Test
    void outWindowNormalRequestAppendsCcbAndMarksOutWindow() throws Exception {
        clearWindows(); // 无启用窗口 → 恒窗口外
        String body = mockMvc.perform(post(REQ).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("[\"ROLE_PLANNER\"]", "华东/OU-01",
                                "窗口外升级链验证需要的普通申请", null, "12", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.outWindow").value("1"))
                .andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).path("data").path("id").asText();
        // 链尾追加 CCB 三方会签（三并行节点）
        org.junit.jupiter.api.Assertions.assertEquals(3, ccbNodeCount(id));
    }

    @Test
    void inWindowRequestHasNoCcb() throws Exception {
        seedAlwaysWindow();
        try {
            String body = mockMvc.perform(post(REQ).header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submitBody("[\"ROLE_PLANNER\"]", "华东/OU-01",
                                    "窗口内提交不升级的正常申请", null, "12", null)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andReturn().getResponse().getContentAsString();
            String id = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(body).path("data").path("id").asText();
            // 窗口内：无 CCB 节点
            org.junit.jupiter.api.Assertions.assertEquals(0, ccbNodeCount(id));
            // 列表过滤：outWindow=false 命中，outWindow=true 不命中
            mockMvc.perform(get(REQ + "?outWindow=false").header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.id == '" + id + "')]").exists());
            mockMvc.perform(get(REQ + "?outWindow=true").header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.id == '" + id + "')]").doesNotExist());
        } finally {
            clearWindows();
        }
    }

    @Test
    void outWindowPrivilegedRequestNotEscalated() throws Exception {
        clearWindows(); // 窗口外
        String body = mockMvc.perform(post(REQ).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("[\"ROLE_ADMIN\"]", "华东/OU-01",
                                "特权窗口外不追加 CCB 的双轨验证", "INC-2026-1029-0002", "1", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.riskLevel").value("PRIVILEGED"))
                .andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).path("data").path("id").asText();
        // 特权窗口外：不追加 CCB（维持挂起等窗口，双轨 D5）
        org.junit.jupiter.api.Assertions.assertEquals(0, ccbNodeCount(id));
        String out = jdbc.queryForObject(
                "SELECT OUT_WINDOW FROM erp_admin_perm_request WHERE ID = ?", String.class, id);
        org.junit.jupiter.api.Assertions.assertEquals("0", out);
    }

    @Test
    void outWindowLedgerShowsCcbSignDetails() throws Exception {
        clearWindows(); // 窗口外 → 触发 CCB 升级
        String body = mockMvc.perform(post(REQ).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("[\"ROLE_PLANNER\"]", "华东/OU-01",
                                "台账三方签署明细验证的申请", null, "12", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).path("data").path("id").asText();

        // 台账端点：本单入账且 CCB 三方节点齐备（未签署状态 PENDING）
        String ledger = mockMvc.perform(get(REQ + "/out-window-ledger")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode arr = m.readTree(ledger).path("data");
        boolean found = false;
        for (com.fasterxml.jackson.databind.JsonNode row : arr) {
            if (id.equals(row.path("id").asText())) {
                found = true;
                org.junit.jupiter.api.Assertions.assertEquals(3, row.path("ccbSigns").size());
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(found, "窗口外申请应在复核台账中");
    }
}
