package com.erp.system;

import com.erp.common.ServiceException;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.bi.MetricDictService;
import com.erp.service.bi.ReportTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报表模板全链路库测（add-report-template-console，spec report-template-management）：
 * 口径 diff 硬阻断（C-4.10-01）/ SQL 白名单 / 三项测试报告与变更作废 /
 * 提交前置与三方审批（自签 403）/ 二次校验回滚（BR-4.10-11）/ 快照回退（BR-4.10-07）/
 * 渲染状态两极权限。
 */
@SpringBootTest
class ReportTemplateDbTest {

    @Autowired
    private ReportTemplateService service;
    @Autowired
    private MetricDictService metricDictService;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String METRIC = "M-RTDB-001";
    private static final String FORMULA = "{\"expr\":\"SUM(AMOUNT)\",\"precision\":2}";

    private String tplId;

    @BeforeEach
    void setUp() {
        purge();
        // 口径字典造数：注册 + 发布（diffCheck 的比对基准）
        Map<String, Object> reg = new java.util.HashMap<>();
        reg.put("metricKey", METRIC);
        reg.put("name", "回款金额");
        reg.put("definition", "测试指标");
        reg.put("formula", FORMULA);
        reg.put("timeDim", "DAY");
        reg.put("srcMapping", "{\"table\":\"fin_receipt\",\"column\":\"AMOUNT\"}");
        // 十二要素（add-metric-dict-console）：新指标 RATIO 型发布须分子分母
        reg.put("formulaType", "RATIO");
        reg.put("numerator", "SUM(AMOUNT)");
        reg.put("denominator", "COUNT(1)");
        metricDictService.register(reg);
        metricDictService.publish(METRIC, FORMULA);
        SystemAuth.login("rtdb-designer", "ROLE_BI_DESIGNER", "ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        SystemAuth.logout();
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_bi_report_version WHERE TEMPLATE_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_bi_report_template WHERE CODE LIKE 'RTDB%') t)");
        jdbc.update("DELETE FROM erp_bi_report_test WHERE TEMPLATE_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_bi_report_template WHERE CODE LIKE 'RTDB%') t)");
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID "
                + "WHERE i.BIZ_TYPE='ReportTemplate'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='ReportTemplate'");
        jdbc.update("DELETE FROM erp_bi_report_template WHERE CODE LIKE 'RTDB%'");
        // 字典为行级历史同表（IS_CURRENT 标记），无独立 history 表
        jdbc.update("DELETE FROM erp_bi_metric_dict WHERE METRIC_KEY=?", METRIC);
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE='ReportTemplatePublish'");
        // 口径字典引用方登记残留
        jdbc.update("UPDATE erp_bi_metric_dict SET USED_BY=NULL WHERE METRIC_KEY=?", METRIC);
    }

    private Map<String, Object> payload(String code, String sql, String metrics) {
        Map<String, Object> p = new java.util.HashMap<>();
        p.put("code", code);
        p.put("name", "测试模板 " + code);
        p.put("category", "STANDARD");
        p.put("sqlText", sql);
        p.put("columnsJson", "[{\"field\":\"AMOUNT\",\"title\":\"金额\"}]");
        p.put("metricsJson", metrics);
        return p;
    }

    private String metricsJson(String formula) {
        // formula 作为 JSON 字符串值：内嵌 JSON 的引号转义后须再包一层引号
        return "[{\"metricKey\":\"" + METRIC + "\",\"formula\":\""
                + formula.replace("\"", "\\\"") + "\"}]";
    }

    // ---------- 登记与口径校验（spec 需求一） ----------

    @Test
    void createRejectsNonSelectSql() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.create(payload("RTDB-DEL", "DELETE FROM erp_bi_metric_dict",
                        metricsJson(FORMULA))));
        assertEquals(422, e.getCode(), "非 SELECT → 422：" + e.getMessage());

        ServiceException e2 = assertThrows(ServiceException.class,
                () -> service.create(payload("RTDB-MULTI",
                        "SELECT 1; DROP TABLE x", metricsJson(FORMULA))));
        assertEquals(422, e2.getCode(), "多语句 → 422");
    }

    @Test
    void createBlocksInconsistentFormula() {
        // 与字典公式不一致（C-4.10-01 L1 阻断，不落库）
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.create(payload("RTDB-BAD",
                        "SELECT 1 AS AMOUNT",
                        metricsJson("{\"expr\":\"AMOUNT*0.9\",\"precision\":2}"))));
        assertEquals(422, e.getCode(), "口径不一致 422：" + e.getMessage());
        assertTrue(e.getMessage().contains("口径"), "提示口径差异：" + e.getMessage());
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_bi_report_template WHERE CODE='RTDB-BAD'",
                Integer.class);
        assertEquals(0, cnt, "阻断后不落库");
    }

    @Test
    void createBlocksUnregisteredMetric() {
        String badMetrics = "[{\"metricKey\":\"rtdb_none\",\"formula\":\"{}\"}]";
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.create(payload("RTDB-UNREG",
                        "SELECT 1 AS X", badMetrics)));
        assertEquals(422, e.getCode(), "未注册 422：" + e.getMessage());
        assertTrue(e.getMessage().contains("未注册"), "提示注册：" + e.getMessage());
    }

    @Test
    void createAndDetailHappyPath() {
        Map<String, Object> out = service.create(payload("RTDB-OK",
                "SELECT 1 AS AMOUNT", metricsJson(FORMULA)));
        tplId = (String) out.get("id");
        assertEquals("DRAFT", out.get("status"), "初始 DRAFT");
        Map<String, Object> d = service.detail(tplId);
        assertNotNull(d.get("template"), "详情可查");
    }

    // ---------- 测试三项报告（spec 需求二） ----------

    @Test
    void testReportFlowAndInvalidateOnChange() {
        tplId = (String) service.create(payload("RTDB-T",
                "SELECT 1 AS AMOUNT", metricsJson(FORMULA))).get("id");

        Map<String, Object> r = service.runTest(tplId);
        String testId = (String) r.get("id");
        assertNotNull(testId, "报告落库");
        // 未核对前总体 FAIL；性能/权限默认达标（小表）
        assertEquals("FAIL", r.get("status"), "未人工核对前 FAIL");

        Map<String, Object> conf = service.confirmAccuracy(testId);
        assertEquals("PASS", conf.get("status"), "核对后三项合取 PASS");

        // 模板状态推进 TESTING
        String st = jdbc.queryForObject(
                "SELECT STATUS FROM erp_bi_report_template WHERE ID=?", String.class, tplId);
        assertEquals("TESTING", st, "测试后 TESTING");

        // 元数据变更 → 报告作废（强制重测）
        Map<String, Object> upd = service.update(tplId,
                payload("RTDB-T", "SELECT 2 AS AMOUNT",
                        metricsJson(FORMULA)));
        assertEquals(Boolean.TRUE, upd.get("metadataChanged"), "SQL 变更检测");
        String valid = jdbc.queryForObject(
                "SELECT VALID FROM erp_bi_report_test WHERE ID=?", String.class, testId);
        assertEquals("0", valid, "旧报告作废");

        // 作废后提交 → 422（无有效 PASS 报告）
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.submit(tplId));
        assertEquals(422, e.getCode(), "无有效报告 422：" + e.getMessage());

        // 重测 → 核对 → 可提交
        String testId2 = (String) service.runTest(tplId).get("id");
        service.confirmAccuracy(testId2);
        Map<String, Object> sub = service.submit(tplId);
        assertEquals("PENDING_APPROVAL", sub.get("status"), "提交后 PENDING");
    }

    // ---------- 三方审批与二次校验（spec 需求三） ----------

    @Test
    void threeNodeApprovalAndSelfSignBlocked() {
        tplId = (String) service.create(payload("RTDB-A",
                "SELECT 1 AS AMOUNT", metricsJson(FORMULA))).get("id");
        String testId = (String) service.runTest(tplId).get("id");
        service.confirmAccuracy(testId);
        Map<String, Object> sub = service.submit(tplId);
        String instId = (String) sub.get("approvalInstanceId");
        assertNotNull(instId, "审批实例");

        // 发起人自签 → 403（rejectSelfSign ReportTemplate 分支）
        String task1 = taskOf(instId, 1);
        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalEngine.pass(task1, "自签"));
        assertEquals(403, e.getCode(), "自签 403：" + e.getMessage());

        // 三方逐签（签署人 ≠ 申请人）
        sign(instId, 1, "ROLE_DATA_GOV", "口径核验通过");
        sign(instId, 2, "ROLE_BIZ_OWNER", "业务准确");
        sign(instId, 3, "ROLE_ADMIN", "性能可接受");

        // 发布生效：PUBLISHED + 快照落库（口径版本绑定）
        String st = jdbc.queryForObject(
                "SELECT STATUS FROM erp_bi_report_template WHERE ID=?", String.class, tplId);
        assertEquals("PUBLISHED", st, "三方全过后 PUBLISHED");
        Integer vers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_bi_report_version WHERE TEMPLATE_ID=?",
                Integer.class, tplId);
        assertEquals(1, vers, "快照 1 条");
        String mv = jdbc.queryForObject(
                "SELECT METRIC_VERSIONS_JSON FROM erp_bi_report_version WHERE TEMPLATE_ID=?",
                String.class, tplId);
        assertNotNull(mv, "口径版本绑定（BR-4.10-07）");
        assertTrue(mv.contains(METRIC), "含指标键：" + mv);
    }

    @Test
    void secondDiffCheckFailureKeepsPreviousVersion() {
        // 首次发布成功
        tplId = (String) service.create(payload("RTDB-2ND",
                "SELECT 1 AS AMOUNT", metricsJson(FORMULA))).get("id");
        String testId = (String) service.runTest(tplId).get("id");
        service.confirmAccuracy(testId);
        Map<String, Object> sub = service.submit(tplId);
        String instId = (String) sub.get("approvalInstanceId");
        sign(instId, 1, "ROLE_DATA_GOV", "通过");
        sign(instId, 2, "ROLE_BIZ_OWNER", "通过");
        sign(instId, 3, "ROLE_ADMIN", "通过");
        Integer v1 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_bi_report_version WHERE TEMPLATE_ID=?",
                Integer.class, tplId);
        assertEquals(1, v1, "首版快照");

        // 新一轮：提交后审批期间口径字典被改（changeFormula 新版本）
        // 重新走草稿 → 测试 → 提交 → 逐签，签最后一环时二次校验应失败
        // 简化：直接构造差异——把模板指标公式改为与字典不一致后走 activateOnApproved 路径
        //（经审批链：先更新模板 metadata 会作废报告并需重测，这里直接调用二次校验路径）
        jdbc.update("UPDATE erp_bi_report_template SET METRICS_JSON=? WHERE ID=?",
                "[{\"metricKey\":\"" + METRIC + "\",\"formula\":\"{\\\"expr\\\":\\\"HACKED\\\"}\"}]",
                tplId);
        // 直接调回调入口（同事务语义由审批链真实路径等价验证——此处直调 activateOnApproved）
        ServiceException e = assertThrows(ServiceException.class,
                () -> ((com.erp.service.impl.bi.ReportTemplateServiceImpl) service)
                        .activateOnApproved(tplId));
        assertEquals(422, e.getCode(), "二次校验失败 422（BR-4.10-11）：" + e.getMessage());
        // 状态保持 PUBLISHED（前一版），告警已发
        String st = jdbc.queryForObject(
                "SELECT STATUS FROM erp_bi_report_template WHERE ID=?", String.class, tplId);
        assertEquals("PUBLISHED", st, "保持前一已发布版本");
        Integer notices = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='ReportTemplatePublish'",
                Integer.class);
        assertTrue(notices >= 1, "告警推送（BI 设计师/数据治理）");
    }

    // ---------- 版本回退（spec 需求四） ----------

    @Test
    void rollbackGeneratesNewVersionKeepingChain() {
        tplId = (String) service.create(payload("RTDB-RB",
                "SELECT 1 AS AMOUNT", metricsJson(FORMULA))).get("id");
        String testId = (String) service.runTest(tplId).get("id");
        service.confirmAccuracy(testId);
        Map<String, Object> sub = service.submit(tplId);
        String instId = (String) sub.get("approvalInstanceId");
        sign(instId, 1, "ROLE_DATA_GOV", "过");
        sign(instId, 2, "ROLE_BIZ_OWNER", "过");
        sign(instId, 3, "ROLE_ADMIN", "过");
        String v1Id = jdbc.queryForObject(
                "SELECT ID FROM erp_bi_report_version WHERE TEMPLATE_ID=? AND VERSION_NO=1",
                String.class, tplId);

        // 草稿修改不影响生效线（ACTIVE_VERSION_ID 不变）
        service.update(tplId, payload("RTDB-RB",
                "SELECT 3 AS AMOUNT", metricsJson(FORMULA)));
        String active1 = jdbc.queryForObject(
                "SELECT ACTIVE_VERSION_ID FROM erp_bi_report_template WHERE ID=?",
                String.class, tplId);
        assertEquals(v1Id, active1, "草稿修改不生效线（D1）");

        // 回退 → 新版本号 2（内容取 V1），历史保留
        Map<String, Object> rb = service.rollback(v1Id);
        assertEquals(2, ((Number) rb.get("versionNo")).intValue(), "回退生成新版本号");
        assertEquals(1, ((Number) rb.get("rollbackFrom")).intValue(), "溯源 V1");
        Integer vers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_bi_report_version WHERE TEMPLATE_ID=?",
                Integer.class, tplId);
        assertEquals(2, vers, "历史保留（V1+V2）");
        // 生效指针切到 V2
        String active2 = jdbc.queryForObject(
                "SELECT ACTIVE_VERSION_ID FROM erp_bi_report_template WHERE ID=?",
                String.class, tplId);
        assertEquals(rb.get("id"), active2, "生效指针切换");
        // V1 内容未被篡改
        String v1Def = jdbc.queryForObject(
                "SELECT DEF_JSON FROM erp_bi_report_version WHERE ID=?", String.class, v1Id);
        assertTrue(v1Def.contains("SELECT 1 AS AMOUNT"), "V1 快照只读保留");
    }

    // ---------- 渲染状态两极权限（spec 需求五） ----------

    @Test
    void renderRespectsPublishStateAndRoles() {
        tplId = (String) service.create(payload("RTDB-R",
                "SELECT 1 AS AMOUNT", metricsJson(FORMULA))).get("id");

        // DRAFT + 普通角色 → 403
        SystemAuth.login("rtdb-plain", "ROLE_PLANNER");
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.render(tplId, 1, 100));
        assertEquals(403, e.getCode(), "非发布态普通角色 403：" + e.getMessage());
        SystemAuth.logout();
        SystemAuth.login("rtdb-designer", "ROLE_BI_DESIGNER", "ROLE_ADMIN");

        // DRAFT + 设计师 → 可预览（测试用）
        Map<String, Object> preview = service.render(tplId, 1, 100);
        assertNotNull(preview.get("rows"), "设计师可预览草稿");

        // 发布 → PUBLISHED 后普通角色可渲染
        String testId = (String) service.runTest(tplId).get("id");
        service.confirmAccuracy(testId);
        Map<String, Object> sub = service.submit(tplId);
        String instId = (String) sub.get("approvalInstanceId");
        sign(instId, 1, "ROLE_DATA_GOV", "过");
        sign(instId, 2, "ROLE_BIZ_OWNER", "过");
        sign(instId, 3, "ROLE_ADMIN", "过");

        SystemAuth.login("rtdb-plain", "ROLE_PLANNER");
        Map<String, Object> rendered = service.render(tplId, 1, 100);
        assertNotNull(rendered.get("rows"), "PUBLISHED 普通角色可渲染");
        assertNotNull(rendered.get("columns"), "列定义返回");
        SystemAuth.logout();
        SystemAuth.login("rtdb-designer", "ROLE_BI_DESIGNER", "ROLE_ADMIN");
    }

    // ---------- helpers ----------

    private String taskOf(String instId, int seq) {
        return jdbc.queryForObject(
                "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID=? AND SEQ=? "
                        + "AND NODE_TYPE='SIGN' ORDER BY ID LIMIT 1",
                String.class, instId, seq);
    }

    private void sign(String instId, int seq, String role, String opinion) {
        String taskId = taskOf(instId, seq);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("signer-" + seq, null,
                        List.of(new SimpleGrantedAuthority(role))));
        try {
            approvalEngine.pass(taskId, opinion);
        } finally {
            SecurityContextHolder.clearContext();
            SystemAuth.login("rtdb-designer", "ROLE_BI_DESIGNER", "ROLE_ADMIN");
        }
    }

    @SuppressWarnings("unused")
    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
