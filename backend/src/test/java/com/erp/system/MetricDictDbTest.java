package com.erp.system;

import com.erp.common.ServiceException;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.bi.MetricDictService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 口径字典治理库测（add-metric-dict-console，spec bi-metric-dictionary）：
 * 十二要素编码校验 / 比率型缺分母阻断 / 存量不追溯 /
 * 变更两段式（治理单签→GRAYING 旧版生效→切换双审 C-4.10-08→IS_CURRENT 切换）/
 * 容差拦截 / 终止灰度 / 引用方标记与确认清除 / 批量口径校验（14.3.3）。
 */
@SpringBootTest
class MetricDictDbTest {

    @Autowired
    private MetricDictService service;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        SystemAuth.login("mdt-designer", "ROLE_BI_DESIGNER", "ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        SystemAuth.logout();
        purge();
    }

    private void purge() {
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i "
                + "ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE IN ('MetricDictChange','MetricDictSwitch')");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN "
                + "('MetricDictChange','MetricDictSwitch')");
        jdbc.update("DELETE FROM erp_bi_metric_lineage WHERE METRIC_KEY LIKE 'M-TST-%' "
                + "OR METRIC_KEY LIKE 'LEGACY%'");
        jdbc.update("DELETE FROM erp_bi_metric_dict WHERE METRIC_KEY LIKE 'M-TST-%' "
                + "OR METRIC_KEY LIKE 'LEGACY%'");
        jdbc.update("DELETE FROM erp_bi_report_template WHERE CODE LIKE 'MDT%'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN "
                + "('MetricDictChange','MetricDictSwitch')");
    }

    // ---------------------------------------------------------------- 造数

    private Map<String, Object> reg(String key, String formula) {
        Map<String, Object> p = new HashMap<>();
        p.put("metricKey", key);
        p.put("name", "测试指标 " + key);
        p.put("definition", "口径字典库测指标");
        p.put("formula", formula);
        p.put("timeDim", "DAY");
        p.put("srcMapping", "{\"Invoice.amount\":\"amt\"}");
        p.put("formulaType", "RATIO");
        p.put("numerator", "销售收入 − 销售成本");
        p.put("denominator", "销售收入");
        p.put("dimensions", "客户/期间");
        p.put("owner", "mdt-owner");
        return p;
    }

    private String registerAndPublish(String key, String formula) {
        service.register(reg(key, formula));
        service.publish(key, formula);
        return key;
    }

    /** 审批签署：签署人 ≠ 发起人（mdt-designer），沿 14.1/14.2 范式 */
    private void sign(String instId, int seq, String role, String opinion) {
        String taskId = jdbc.queryForObject(
                "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID=? AND SEQ=? "
                        + "AND NODE_TYPE='SIGN' ORDER BY ID LIMIT 1",
                String.class, instId, seq);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("signer-" + seq, null,
                        List.of(new SimpleGrantedAuthority(role))));
        try {
            approvalEngine.pass(taskId, opinion);
        } finally {
            SecurityContextHolder.clearContext();
            SystemAuth.login("mdt-designer", "ROLE_BI_DESIGNER", "ROLE_ADMIN");
        }
    }

    private String statusOf(String key, int version) {
        return jdbc.queryForObject(
                "SELECT STATUS FROM erp_bi_metric_dict WHERE METRIC_KEY=? AND VERSION=?",
                String.class, key, version);
    }

    private Boolean isCurrentOf(String key, int version) {
        return jdbc.queryForObject(
                "SELECT IS_CURRENT FROM erp_bi_metric_dict WHERE METRIC_KEY=? AND VERSION=?",
                Boolean.class, key, version);
    }

    // ---------------- 十二要素与编码格式（spec 6.3.1） ----------------

    @Test
    void newKeyRequiresCodeFormat() {
        Map<String, Object> p = reg("SALES_RATE", "{\"expr\":\"SUM(a)\"}");
        ServiceException e = assertThrows(ServiceException.class, () -> service.register(p));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("M-"), "提示编码格式：" + e.getMessage());
        // 合法格式通过
        Map<String, Object> ok = service.register(reg("M-TST-001", "{\"expr\":\"SUM(a)\"}"));
        assertEquals("DRAFT", ok.get("status"));
    }

    @Test
    void ratioPublishRequiresNumeratorDenominator() {
        Map<String, Object> p = reg("M-TST-002", "{\"expr\":\"SUM(a)\"}");
        p.put("numerator", null);
        p.put("denominator", null);
        service.register(p);
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.publish("M-TST-002", "{\"expr\":\"SUM(a)\"}"));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("分子"), e.getMessage());
        // 补全要素后发布通过
        service.register(reg("M-TST-002", "{\"expr\":\"SUM(a)\"}"));
        assertEquals("PUBLISHED",
                service.publish("M-TST-002", "{\"expr\":\"SUM(a)\"}").get("status"));
    }

    @Test
    void legacyKeyNotBlockedByNewFormat() {
        // 存量编码不追溯（spec 场景）：直插 LEGACY_KEY 已发布行 → 变更流程正常
        jdbc.update("INSERT INTO erp_bi_metric_dict (ID, METRIC_KEY, NAME, DEFINITION, FORMULA, "
                        + "DATA_PRECISION, TIME_DIM, SRC_MAPPING, VERSION, IS_CURRENT, STATUS, "
                        + "USED_BY, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES (?, 'LEGACY_KEY', '存量指标', '旧编码', "
                        + "'{\"expr\":\"SUM(x)\"}', 2, 'DAY', '{}', 1, 1, 'PUBLISHED', "
                        + "'[]', 'seed', NOW(), '0', 0)",
                UUID.randomUUID().toString().replace("-", ""));
        Map<String, Object> out = service.requestChange("LEGACY_KEY",
                Map.of("formula", "{\"expr\":\"SUM(y)\"}", "reason", "存量变更"));
        assertEquals("CHANGE_PENDING", out.get("status"), "存量编码变更不被格式拦截");
    }

    // ---------------- 变更两段式（BR-4.10-04 / C-4.10-08） ----------------

    @Test
    void changeChainGovernanceThenGrayingThenSwitch() {
        registerAndPublish("M-TST-010", "{\"expr\":\"SUM(a)\"}");
        // 引用方登记（反扫对象）
        service.bindUsage("M-TST-010", "tpl-consumer");

        Map<String, Object> req = service.requestChange("M-TST-010",
                Map.of("formula", "{\"expr\":\"SUM(b)\"}", "reason", "口径调整",
                        "scope", "涉及1张报表"));
        assertEquals("CHANGE_PENDING", req.get("status"));
        assertEquals(2, req.get("version"));
        // 未审批不生效：旧版仍 IS_CURRENT
        assertEquals(Boolean.TRUE, isCurrentOf("M-TST-010", 1), "旧版仍生效");
        assertEquals(Boolean.FALSE, isCurrentOf("M-TST-010", 2), "新版未生效");

        // 同指标重复申请 422
        ServiceException dup = assertThrows(ServiceException.class,
                () -> service.requestChange("M-TST-010",
                        Map.of("formula", "{\"expr\":\"SUM(c)\"}")));
        assertEquals(422, dup.getCode());

        // 治理单签 → GRAYING（旧版仍生效 + 引用方标记 + 通知）
        sign((String) req.get("approvalInstanceId"), 1, "ROLE_DATA_GOV", "一致性核验通过");
        assertEquals("GRAYING", statusOf("M-TST-010", 2));
        assertEquals(Boolean.TRUE, isCurrentOf("M-TST-010", 1), "灰度期旧版仍生效");
        Map<String, Object> cs = service.consumerStatus("M-TST-010");
        assertTrue(Boolean.TRUE.equals(cs.get("pending")), "引用方待更新标记");
        Integer notices = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='MetricDictChange'",
                Integer.class);
        assertTrue(notices >= 1, "灰度通知 ROLE_BI_DESIGNER");

        // diffCheck 基准仍是旧版公式（14.1 语义正确）
        Map<String, Object> diff = service.diffCheck("M-TST-010", "{\"expr\":\"SUM(a)\"}");
        assertEquals(Boolean.TRUE, diff.get("consistent"), "灰度期 diff 按旧版");

        // 切换确认：超容差缺复核备注 422
        ServiceException tol = assertThrows(ServiceException.class,
                () -> service.switchConfirm((String) req.get("id"), "新旧差异说明", "0.8", null));
        assertEquals(422, tol.getCode());
        assertTrue(tol.getMessage().contains("容差"), tol.getMessage());
        // 容差内（或附复核备注）→ 双审
        Map<String, Object> sw = service.switchConfirm((String) req.get("id"),
                "新版剔除返利，差异 0.3%", "0.3", null);
        // SEQ=1 数据治理、SEQ=2 业务负责人
        sign((String) sw.get("approvalInstanceId"), 1, "ROLE_DATA_GOV", "灰度复核通过");
        assertEquals("GRAYING", statusOf("M-TST-010", 2), "双审未完不生效");
        sign((String) sw.get("approvalInstanceId"), 2, "ROLE_BIZ_OWNER", "切换批准");

        // 新版生效：IS_CURRENT 切换
        assertEquals("PUBLISHED", statusOf("M-TST-010", 2));
        assertEquals(Boolean.TRUE, isCurrentOf("M-TST-010", 2), "新版生效");
        assertEquals(Boolean.FALSE, isCurrentOf("M-TST-010", 1), "旧版退位");
        Integer switchNotices = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='MetricDictSwitch'",
                Integer.class);
        assertTrue(switchNotices >= 1, "切换生效通知");

        // 引用方更新确认清除标记（历史保留）
        Map<String, Object> cc = service.confirmConsumer("M-TST-010", "tpl-consumer");
        assertEquals("ACTIVE", cc.get("status"));
        Map<String, Object> cs2 = service.consumerStatus("M-TST-010");
        assertEquals(Boolean.FALSE, cs2.get("pending"), "确认后清除待更新");
    }

    @Test
    void overToleranceSwitchRequiresReviewNote() {
        registerAndPublish("M-TST-011", "{\"expr\":\"SUM(a)\"}");
        Map<String, Object> req = service.requestChange("M-TST-011",
                Map.of("formula", "{\"expr\":\"SUM(bb)\"}"));
        sign((String) req.get("approvalInstanceId"), 1, "ROLE_DATA_GOV", "通过");
        // 超 0.5% 附治理复核备注 → 允许提交
        Map<String, Object> sw = service.switchConfirm((String) req.get("id"),
                "历史重算差异 0.8%", "0.8", "治理已复核存量数据补录影响，同意切换");
        assertNotNull(sw.get("approvalInstanceId"), "附复核备注后双审可提交");
    }

    @Test
    void abortGrayingKeepsOldVersion() {
        registerAndPublish("M-TST-012", "{\"expr\":\"SUM(a)\"}");
        Map<String, Object> req = service.requestChange("M-TST-012",
                Map.of("formula", "{\"expr\":\"SUM(zz)\"}"));
        sign((String) req.get("approvalInstanceId"), 1, "ROLE_DATA_GOV", "通过");
        Map<String, Object> ab = service.abortChange((String) req.get("id"), "业务方放弃调整");
        assertEquals("ABORTED", ab.get("status"));
        // 旧版续用
        assertEquals(Boolean.TRUE, isCurrentOf("M-TST-012", 1), "终止后旧版续用");
        assertEquals(Boolean.FALSE, isCurrentOf("M-TST-012", 2), "新版不生效");
    }

    @Test
    void governanceRejectReturnsDraft() {
        registerAndPublish("M-TST-013", "{\"expr\":\"SUM(a)\"}");
        Map<String, Object> req = service.requestChange("M-TST-013",
                Map.of("formula", "{\"expr\":\"SUM(rr)\"}"));
        String instId = (String) req.get("approvalInstanceId");
        String taskId = jdbc.queryForObject(
                "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID=? AND SEQ=1 "
                        + "AND NODE_TYPE='SIGN' ORDER BY ID LIMIT 1", String.class, instId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("signer-1", null,
                        List.of(new SimpleGrantedAuthority("ROLE_DATA_GOV"))));
        try {
            approvalEngine.reject(taskId, "定义不清退回");
        } finally {
            SecurityContextHolder.clearContext();
            SystemAuth.login("mdt-designer", "ROLE_BI_DESIGNER", "ROLE_ADMIN");
        }
        assertEquals("DRAFT", statusOf("M-TST-013", 2), "驳回退回草稿");
        assertEquals(Boolean.TRUE, isCurrentOf("M-TST-013", 1), "驳回后旧版生效");
    }

    // ---------------- 批量口径校验（14.3.3） ----------------

    @Test
    void checkAllReportsThreeConclusions() {
        registerAndPublish("M-TST-020", "{\"expr\":\"SUM(a)\"}");
        // 已发布模板：指标一致 + 未注册 + 差异 三态
        jdbc.update("INSERT INTO erp_bi_report_template (ID, CODE, NAME, CATEGORY, SQL_TEXT, "
                        + "COLUMNS_JSON, METRICS_JSON, STATUS, CREATE_BY, CREATE_DATE, "
                        + "DEL_FLAG, VER_NO) VALUES (?,?,?,'STANDARD','SELECT 1','[]',?,"
                        + "'PUBLISHED','mdt-test',NOW(),'0',0)",
                uuid(), "MDT-OK", "一致模板",
                "[{\"metricKey\":\"M-TST-020\",\"formula\":\"{\\\"expr\\\":\\\"SUM(a)\\\"}\"}]");
        jdbc.update("INSERT INTO erp_bi_report_template (ID, CODE, NAME, CATEGORY, SQL_TEXT, "
                        + "COLUMNS_JSON, METRICS_JSON, STATUS, CREATE_BY, CREATE_DATE, "
                        + "DEL_FLAG, VER_NO) VALUES (?,?,?,'STANDARD','SELECT 1','[]',?,"
                        + "'PUBLISHED','mdt-test',NOW(),'0',0)",
                uuid(), "MDT-BAD", "差异模板",
                "[{\"metricKey\":\"M-TST-020\",\"formula\":\"{\\\"expr\\\":\\\"HACKED\\\"}\"},"
                        + "{\"metricKey\":\"M-TST-099\",\"formula\":\"{}\"}]");

        List<Map<String, Object>> report = service.checkAll();
        Map<String, Object> okRow = report.stream()
                .filter(r -> "MDT-OK".equals(r.get("templateCode"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, okRow.get("consistent"), "一致模板结论");

        Map<String, Object> badRow = report.stream()
                .filter(r -> "MDT-BAD".equals(r.get("templateCode"))
                        && "M-TST-020".equals(r.get("metricKey"))).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, badRow.get("consistent"), "差异模板结论");

        Map<String, Object> unreg = report.stream()
                .filter(r -> "M-TST-099".equals(r.get("metricKey"))).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, unreg.get("registered"), "未注册计为差异");
    }

    // ---------------- 既有消费面零回归 ----------------

    @Test
    void diffCheckAndPublishStillConsistentFlow() {
        registerAndPublish("M-TST-030", "{\"expr\":\"SUM(v)\"}");
        Map<String, Object> d = service.diffCheck("M-TST-030", "{\"expr\":\"SUM(v)\"}");
        assertEquals(Boolean.TRUE, d.get("consistent"), "diffCheck 消费面不变（14.1）");
        assertNotNull(service.history("M-TST-030"), "历史版本可查");
        assertNotNull(service.current("M-TST-030"), "当前版可查");
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
