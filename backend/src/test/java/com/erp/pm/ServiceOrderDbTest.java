package com.erp.pm;

import com.erp.common.ServiceException;
import com.erp.service.impl.pm.PmServiceOrderServiceImpl;
import com.erp.service.pm.PmServiceOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.core.context.SecurityContextHolder.getContext;

/**
 * 服务工单 DbTest（add-service-order，spec service-order）：
 * 登记 L1/自动定级/一级通知、查重标注、定级留痕、派单改派/待指派升级/负载推荐、
 * 根因卡控/设计缺陷抄送、NCR 卡控（BR-4.8-36）、挂起暂停计时、驳回重计时+2 次升级、
 * 确认双通道（代录/token 一次性）、默认关闭、回访+低分改进任务、升级幂等（BR-4.8-03/06）。
 * 造数前缀 so-，客户 so-cust-1。
 */
@SpringBootTest
class ServiceOrderDbTest {

    @Autowired
    private PmServiceOrderService service;
    @Autowired
    private PmServiceOrderServiceImpl impl;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String CUST = "so-cust-1";

    @BeforeEach
    void setUp() {
        purge();
        seedCustomer(CUST, "SO 测试客户");
        seedEngineer("so-t-eng1", "服务甲");
        seedEngineer("so-t-eng2", "服务乙");
        authAs("so-t-cs", "CS");
    }

    @AfterEach
    void tearDown() {
        purge();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    // ==================== 登记 / 定级 ====================

    @Test
    void registerMissingCustomerBlocked() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.register(payload("so-cust-x", "PRODUCT_QUALITY", "PRODUCTION", "机器冒烟")));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("客户"), e.getMessage());
    }

    @Test
    void registerMissingDescriptionBlocked() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.register(payload(CUST, "PRODUCT_QUALITY", "PRODUCTION", "")));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("描述"), e.getMessage());
    }

    @Test
    void registerAutoGradeProductionLevel1NotifiesSalesMgr() {
        Map<String, Object> o = service.register(
                payload(CUST, "PRODUCT_QUALITY", "PRODUCTION", "产线停机，重大质量事故"));
        assertEquals(1, o.get("level"));
        assertEquals("REGISTERED", o.get("status"));
        assertNotNull(o.get("respDeadline"));
        // 一级自动通知销售经理（FR-4.8-9-2）
        Integer notified = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE TARGET_ROLE='ROLE_SALES_MGR' "
                        + "AND BIZ_TYPE='PmServiceOrder' AND TITLE LIKE '%一级投诉%'", Integer.class);
        assertTrue(notified >= 1, "一级投诉应通知销售经理");
    }

    @Test
    void registerAutoGradeNormalLevel3() {
        Map<String, Object> o = service.register(
                payload(CUST, "SERVICE_ATTITUDE", "NORMAL", "服务态度一般投诉"));
        assertEquals(3, o.get("level"));
    }

    @Test
    void registerGradeMappingConfigurable() {
        // 后台改映射：NORMAL → L1（定级规则可配置 FR-4.8-9-2）
        jdbc.update("UPDATE erp_pm_service_sla_grade SET LEVEL=1, RESP_HOURS=1, RESOLVE_HOURS=2 "
                + "WHERE IMPACT_LEVEL='NORMAL'");
        try {
            Map<String, Object> o = service.register(
                    payload(CUST, "SERVICE_ATTITUDE", "NORMAL", "映射可配验证"));
            assertEquals(1, o.get("level"));
        } finally {
            jdbc.update("UPDATE erp_pm_service_sla_grade SET LEVEL=3, RESP_HOURS=8, RESOLVE_HOURS=168 "
                    + "WHERE IMPACT_LEVEL='NORMAL'");
        }
    }

    // ==================== 重复投诉标注 ====================

    @Test
    void duplicateCandidatesShownAndMarkLinked() {
        Map<String, Object> first = service.register(
                payload(CUST, "FUNCTION_DEFECT", "CORE", "功能缺陷首次投诉"));
        Map<String, Object> second = service.register(
                payload(CUST, "FUNCTION_DEFECT", "CORE", "功能缺陷再次投诉"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cands =
                (List<Map<String, Object>>) second.get("duplicateCandidates");
        assertEquals(1, cands.size(), "应命中前单候选");
        service.markDuplicate(String.valueOf(second.get("id")),
                Map.of("duplicateOf", String.valueOf(first.get("id"))));
        // SLA 独立计时：两张单各有各的截止
        Map<String, Object> d1 = service.detail(String.valueOf(first.get("id")));
        Map<String, Object> d2 = service.detail(String.valueOf(second.get("id")));
        assertNull(d1.get("duplicateOf"));
        assertEquals(first.get("id"), d2.get("duplicateOf"));
    }

    @Test
    void markDuplicateSelfBlocked() {
        Map<String, Object> o = service.register(payload(CUST, "OTHER", "NORMAL", "自查重"));
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.markDuplicate(String.valueOf(o.get("id")),
                        Map.of("duplicateOf", String.valueOf(o.get("id")))));
        assertEquals(422, e.getCode());
    }

    // ==================== 定级调整留痕 ====================

    @Test
    void adjustLevelRequiresReason() {
        Map<String, Object> o = service.register(payload(CUST, "OTHER", "NORMAL", "定级调整"));
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.adjustLevel(String.valueOf(o.get("id")), Map.of("level", 1)));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("理由"), e.getMessage());
    }

    @Test
    void adjustLevelLeavesBeforeAfterTrail() {
        Map<String, Object> o = service.register(payload(CUST, "OTHER", "NORMAL", "定级留痕"));
        assertEquals(3, o.get("level"));
        service.adjustLevel(String.valueOf(o.get("id")),
                Map.of("level", 1, "reason", "客户为战略客户，按重大投诉处理"));
        Map<String, Object> d = service.detail(String.valueOf(o.get("id")));
        assertEquals(1, d.get("level"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) d.get("records");
        boolean trail = records.stream().anyMatch(r ->
                String.valueOf(r.get("content")).contains("L3 → L1")
                        && String.valueOf(r.get("content")).contains("战略客户"));
        assertTrue(trail, "定级调整须留 Before/After + 理由");
    }

    // ==================== 派单 / 改派 ====================

    @Test
    void dispatchRequiresCsRole() {
        Map<String, Object> o = service.register(payload(CUST, "OTHER", "NORMAL", "派单权限"));
        authAs("so-t-eng1", "SERVICE_ENG");
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.dispatch(String.valueOf(o.get("id")),
                        Map.of("engineerId", "so-t-eng1")));
        assertEquals(403, e.getCode());
    }

    @Test
    void dispatchAndResignLeavesTrail() {
        Map<String, Object> o = service.register(payload(CUST, "OTHER", "NORMAL", "派单改派"));
        service.dispatch(String.valueOf(o.get("id")),
                Map.of("engineerId", "so-t-eng1", "engineerName", "服务甲"));
        service.dispatch(String.valueOf(o.get("id")),
                Map.of("engineerId", "so-t-eng2", "engineerName", "服务乙"));
        Map<String, Object> d = service.detail(String.valueOf(o.get("id")));
        assertEquals("so-t-eng2", d.get("engineerId"));
        assertEquals("DISPATCHED", d.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) d.get("records");
        long resigns = records.stream()
                .filter(r -> String.valueOf(r.get("content")).contains("改派"))
                .count();
        assertTrue(resigns >= 1, "改派须留痕");
    }

    @Test
    void dispatchNoEngineerNotifiesMgr() {
        Map<String, Object> o = service.register(payload(CUST, "OTHER", "NORMAL", "待指派"));
        service.dispatch(String.valueOf(o.get("id")), Map.of());
        Integer notified = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE TARGET_ROLE='ROLE_SERVICE_MGR' "
                        + "AND BIZ_TYPE='PmServiceOrderDispatch'", Integer.class);
        assertTrue(notified >= 1, "待指派应通知服务主管");
    }

    @Test
    void recommendEngineerLeastLoadFirst() {
        // eng1 已有 1 张在手，eng2 空闲 → 推荐 eng2
        Map<String, Object> o1 = service.register(payload(CUST, "OTHER", "NORMAL", "负载一"));
        service.dispatch(String.valueOf(o1.get("id")),
                Map.of("engineerId", "so-t-eng1", "engineerName", "服务甲"));
        Map<String, Object> rec = service.recommendEngineer();
        assertEquals("so-t-eng2", rec.get("recommended") == null ? null
                : ((Map<?, ?>) rec.get("recommended")).get("userId"));
    }

    // ==================== 受理 / 处理记录 ====================

    @Test
    void acceptSetsRespondedAndProcessing() {
        Map<String, Object> o = service.register(payload(CUST, "OTHER", "NORMAL", "受理"));
        service.dispatch(String.valueOf(o.get("id")),
                Map.of("engineerId", "so-t-eng1", "engineerName", "服务甲"));
        authAs("so-t-eng1", "SERVICE_ENG");
        Map<String, Object> d = service.accept(String.valueOf(o.get("id")));
        assertNotNull(d.get("respondedAt"), "受理须记响应时间");
        assertEquals("PROCESSING", d.get("status"));
    }

    @Test
    void appendProcessAppendsOnly() {
        Map<String, Object> o = service.register(payload(CUST, "OTHER", "NORMAL", "仅追加"));
        String id = String.valueOf(o.get("id"));
        service.appendProcess(id, Map.of("content", "第一次处理"));
        service.appendProcess(id, Map.of("content", "第二次处理"));
        Map<String, Object> d = service.detail(id);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) d.get("records");
        assertTrue(records.size() >= 3, "记录只增不减（登记+两次追加）");
        // 无 UPDATE/DELETE 入口：服务接口不存在对应方法（BR-4.8-35 结构性保证）
    }

    // ==================== 根因 / NCR 卡控 ====================

    @Test
    void submitConfirmBlockedWithoutRootCause() {
        Map<String, Object> o = service.register(payload(CUST, "OTHER", "NORMAL", "根因卡控"));
        String id = String.valueOf(o.get("id"));
        service.dispatch(id, Map.of("engineerId", "so-t-eng1"));
        authAs("so-t-eng1", "SERVICE_ENG");
        service.accept(id);
        ServiceException e = assertThrows(ServiceException.class, () -> service.submitConfirm(id));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("根因"), e.getMessage());
    }

    @Test
    void saveRootCauseDesignNotifiesRdLead() {
        Map<String, Object> o = service.register(payload(CUST, "FUNCTION_DEFECT", "CORE", "设计缺陷"));
        service.saveRootCause(String.valueOf(o.get("id")), Map.of("rootCause", "DESIGN"));
        Integer notified = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE TARGET_ROLE='ROLE_RD_LEAD' "
                        + "AND BIZ_TYPE='PmServiceOrderDesign'", Integer.class);
        assertTrue(notified >= 1, "设计缺陷须抄送研发负责人");
    }

    @Test
    void markCuredQualityWithoutNcrBlocked() {
        Map<String, Object> o = service.register(
                payload(CUST, "PRODUCT_QUALITY", "CORE", "质量类无 NCR"));
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.markCured(String.valueOf(o.get("id"))));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("NCR"), e.getMessage());
    }

    @Test
    void markCuredNonQualityPassesWithoutNcr() {
        Map<String, Object> o = service.register(
                payload(CUST, "SERVICE_ATTITUDE", "NORMAL", "非质量类"));
        service.saveRootCause(String.valueOf(o.get("id")), Map.of("rootCause", "EXTERNAL"));
        Map<String, Object> d = service.markCured(String.valueOf(o.get("id")));
        assertEquals("1", d.get("rootCured"));
    }

    @Test
    void linkNcrExistenceChecked() {
        Map<String, Object> o = service.register(
                payload(CUST, "PRODUCT_QUALITY", "CORE", "NCR 校验"));
        // 不存在 → 422
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.linkNcr(String.valueOf(o.get("id")), Map.of("ncrNo", "NCR-NO-999")));
        assertEquals(422, e.getCode());
        // 存在 → 放行并可标已根治
        seedNcr("so-ncr-1", "NCR-SO-001");
        Map<String, Object> d = service.linkNcr(String.valueOf(o.get("id")),
                Map.of("ncrNo", "NCR-SO-001"));
        assertEquals("NCR-SO-001", d.get("ncrNo"));
        assertEquals("1", service.markCured(String.valueOf(o.get("id"))).get("rootCured"));
    }

    // ==================== 挂起 / 恢复计时 ====================

    @Test
    void suspendPausesSlaAndResumeExtends() {
        Map<String, Object> o = service.register(payload(CUST, "OTHER", "NORMAL", "挂起计时"));
        String id = String.valueOf(o.get("id"));
        String resolveBefore = String.valueOf(o.get("resolveDeadline"));
        service.suspend(id, Map.of("reason", "客户信息缺失"));
        // 二次挂起 409
        assertThrows(ServiceException.class, () -> service.suspend(id, Map.of()));
        // 把挂起起点回拨 2 小时再恢复 → 累计挂起≈2h，解决截止顺延
        jdbc.update("UPDATE erp_pm_service_order SET SUSPEND_AT = DATE_SUB(NOW(), INTERVAL 2 HOUR) "
                + "WHERE ID=?", id);
        Map<String, Object> d = service.resume(id);
        assertEquals("0", d.get("suspendedFlag"));
        assertTrue(new BigDecimal(String.valueOf(d.get("suspendHours")))
                .compareTo(new BigDecimal("1.9")) > 0, "累计挂起应≈2h：" + d.get("suspendHours"));
        assertTrue(String.valueOf(d.get("resolveDeadline")).compareTo(resolveBefore) > 0,
                "解决截止应顺延");
        // 未挂起再恢复 → 422
        assertThrows(ServiceException.class, () -> service.resume(id));
    }

    @Test
    void suspendedOrderSkippedByEscalation() {
        Map<String, Object> o = service.register(payload(CUST, "OTHER", "NORMAL", "挂起不升级"));
        String id = String.valueOf(o.get("id"));
        service.suspend(id, Map.of("reason", "待客户信息"));
        jdbc.update("UPDATE erp_pm_service_order SET RESP_DEADLINE = DATE_SUB(NOW(), INTERVAL 1 HOUR) "
                + "WHERE ID=?", id);
        impl.escalateOverdue(LocalDateTime.now());
        assertEquals(0, jdbc.queryForObject(
                "SELECT ESCALATE_STAGE FROM erp_pm_service_order WHERE ID=?", Integer.class, id),
                "挂起期间 SLA 暂停，不升级");
    }

    // ==================== 超时升级幂等 ====================

    @Test
    void escalateOverdueIdempotentAndChain() {
        Map<String, Object> o = service.register(payload(CUST, "OTHER", "NORMAL", "超时升级"));
        String id = String.valueOf(o.get("id"));
        // 仅回拨响应截止 → 第一档升级主管（解决截止未超不触发第二档）
        jdbc.update("UPDATE erp_pm_service_order SET RESP_DEADLINE = DATE_SUB(NOW(), INTERVAL 1 HOUR) "
                + "WHERE ID=?", id);
        impl.escalateOverdue(LocalDateTime.now());
        assertEquals(1, jdbc.queryForObject(
                "SELECT ESCALATE_STAGE FROM erp_pm_service_order WHERE ID=?", Integer.class, id));
        // 重复扫描幂等：不再升级、不重复通知
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM erp_pm_service_sla_log "
                + "WHERE ORDER_ID=?", Integer.class, id);
        impl.escalateOverdue(LocalDateTime.now());
        int after = jdbc.queryForObject("SELECT COUNT(*) FROM erp_pm_service_sla_log "
                + "WHERE ORDER_ID=?", Integer.class, id);
        assertEquals(before, after, "重复扫描不产生新 SLA 事件");
        // 回拨解决截止（已响应）→ 第二档升级销售经理
        jdbc.update("UPDATE erp_pm_service_order SET RESPONDED_AT = NOW(), "
                + "RESOLVE_DEADLINE = DATE_SUB(NOW(), INTERVAL 1 HOUR) WHERE ID=?", id);
        impl.escalateOverdue(LocalDateTime.now());
        assertEquals(2, jdbc.queryForObject(
                "SELECT ESCALATE_STAGE FROM erp_pm_service_order WHERE ID=?", Integer.class, id));
    }

    // ==================== 确认双通道 / 驳回 ====================

    @Test
    void agentConfirmResolvedCloses() {
        String id = readyForConfirm("代录确认");
        Map<String, Object> d = service.agentConfirm(id, Map.of("result", "RESOLVED"));
        assertEquals("CLOSED", d.get("status"));
        assertEquals("AGENT", d.get("confirmedVia"));
        assertEquals("RESOLVED", d.get("confirmResult"));
    }

    @Test
    void agentRejectReprocessesAndReTimers() {
        String id = readyForConfirm("驳回重计时");
        // 把解决截止显式回拨（DB datetime 秒级精度，同秒重算无法区分新旧）
        jdbc.update("UPDATE erp_pm_service_order SET RESOLVE_DEADLINE = DATE_ADD(NOW(), INTERVAL 10 HOUR) "
                + "WHERE ID=?", id);
        Map<String, Object> d = service.agentConfirm(id, Map.of("result", "REJECTED"));
        assertEquals("PROCESSING", d.get("status"));
        assertEquals(1, d.get("rejectCount"));
        // 重计时：从 DB 重载，新截止 = 驳回时刻 + 168h（L3），远大于回拨的 10h
        LocalDateTime newDeadline = LocalDateTime.parse(
                String.valueOf(service.detail(id).get("resolveDeadline")));
        assertTrue(newDeadline.isAfter(LocalDateTime.now().plusHours(100)),
                "驳回后解决截止应重计时：new=" + newDeadline);
    }

    @Test
    void secondRejectEscalatesMgr() {
        String id = readyForConfirm("两次驳回");
        service.agentConfirm(id, Map.of("result", "REJECTED"));
        // 退回处理后再次提交确认，客户再驳回 → 第 2 次驳回升级主管
        service.submitConfirm(id);
        Map<String, Object> d = service.agentConfirm(id, Map.of("result", "REJECTED"));
        assertEquals(2, d.get("rejectCount"));
        assertEquals(1, d.get("escalateStage"), "驳回 ≥2 次升级服务主管");
    }

    @Test
    void tokenConfirmOneTimeAndResolves() {
        String id = readyForConfirm("token 确认");
        @SuppressWarnings("unchecked")
        Map<String, Object> link = service.generateConfirmLink(id);
        String token = String.valueOf(link.get("token"));
        assertNotNull(service.tokenView(token));
        Map<String, Object> d = service.tokenConfirm(token, Map.of("result", "RESOLVED"));
        assertEquals("CLOSED", d.get("status"));
        assertEquals("LINK", d.get("confirmedVia"));
        // 一次性：二次使用 422
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.tokenConfirm(token, Map.of("result", "RESOLVED")));
        assertEquals(422, e.getCode());
    }

    @Test
    void tokenExpiredBlocked() {
        String id = readyForConfirm("过期 token");
        @SuppressWarnings("unchecked")
        Map<String, Object> link = service.generateConfirmLink(id);
        String token = String.valueOf(link.get("token"));
        jdbc.update("UPDATE erp_pm_service_confirm_token SET EXPIRE_AT = DATE_SUB(NOW(), INTERVAL 1 DAY) "
                + "WHERE TOKEN=?", token);
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.tokenView(token));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("过期"), e.getMessage());
    }

    // ==================== 默认关闭 ====================

    @Test
    void defaultCloseAfterTwoReminders() {
        String id = readyForConfirm("默认关闭");
        // 提交确认时间回拨 10 天（>5 工作日 + 2 提醒 + 1 天）
        jdbc.update("UPDATE erp_pm_service_order SET CONFIRM_SUBMIT_AT = DATE_SUB(NOW(), INTERVAL 10 DAY) "
                + "WHERE ID=?", id);
        int closed = impl.defaultCloseExpired(LocalDateTime.now());
        assertEquals(1, closed);
        Map<String, Object> d = service.detail(id);
        assertEquals("CLOSED", d.get("status"));
        assertEquals("DEFAULT", d.get("confirmedVia"));
        assertEquals("1", d.get("closeDefaultFlag"), "须标注默认关闭");
        Integer reminds = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_service_sla_log WHERE ORDER_ID=? "
                        + "AND EVENT='REMIND_CONFIRM'", Integer.class, id);
        assertEquals(2, reminds, "默认关闭前应有 2 次提醒记录");
    }

    // ==================== 回访 / 改进任务 ====================

    @Test
    void surveyScoreRangeChecked() {
        String id = readyForConfirm("评分范围");
        service.agentConfirm(id, Map.of("result", "RESOLVED"));
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.saveSurvey(id, Map.of("score", 6)));
        assertEquals(422, e.getCode());
    }

    @Test
    void lowScoreCreatesImproveTask() {
        String id = readyForConfirm("低分改进");
        service.agentConfirm(id, Map.of("result", "RESOLVED"));
        Map<String, Object> out = service.saveSurvey(id,
                Map.of("score", 2, "suggestion", "响应太慢"));
        assertNotNull(out.get("improveTask"), "≤2 分须自动生成改进任务");
        @SuppressWarnings("unchecked")
        Map<String, Object> task = (Map<String, Object>) out.get("improveTask");
        assertEquals("PENDING", task.get("status"));
        assertNotNull(task.get("dueDate"), "改进任务须有 10 工作日期限");
        // 防重：二次回访 409
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.saveSurvey(id, Map.of("score", 5)));
        assertEquals(409, e.getCode());
    }

    @Test
    void improveOverdueEscalatesSalesMgr() {
        String id = readyForConfirm("改进逾期");
        service.agentConfirm(id, Map.of("result", "RESOLVED"));
        service.saveSurvey(id, Map.of("score", 1, "suggestion", "严重延迟"));
        String taskId = jdbc.queryForObject(
                "SELECT ID FROM erp_pm_service_improve_task WHERE ORDER_ID=?", String.class, id);
        jdbc.update("UPDATE erp_pm_service_improve_task SET DUE_DATE = DATE_SUB(NOW(), INTERVAL 1 DAY) "
                + "WHERE ID=?", taskId);
        int n = impl.escalateImproveOverdue(LocalDateTime.now());
        assertEquals(1, n);
        assertEquals("1", jdbc.queryForObject(
                "SELECT ESCALATED_SALES FROM erp_pm_service_improve_task WHERE ID=?",
                String.class, taskId));
        // 幂等：重复扫描不再升级
        assertEquals(0, impl.escalateImproveOverdue(LocalDateTime.now()));
    }

    @Test
    void unreachedSurveyArchives() {
        String id = readyForConfirm("未回访");
        service.agentConfirm(id, Map.of("result", "RESOLVED"));
        service.saveSurvey(id, Map.of("unreached", "1", "contactTries", 3));
        Map<String, Object> d = service.detail(id);
        @SuppressWarnings("unchecked")
        Map<String, Object> survey = (Map<String, Object>) d.get("survey");
        assertEquals("1", survey.get("unreachedFlag"));
        assertNull(survey.get("score"));
    }

    @Test
    void surveyStatsThreeDimensions() {
        String id = readyForConfirm("统计");
        service.agentConfirm(id, Map.of("result", "RESOLVED"));
        service.saveSurvey(id, Map.of("score", 5, "suggestion", "很满意"));
        Map<String, Object> stats = service.surveyStats(Map.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byCustomer = (List<Map<String, Object>>) stats.get("byCustomer");
        assertTrue(byCustomer.stream().anyMatch(r -> CUST.equals(r.get("customerId"))),
                "按客户维度应含测试客户");
        assertNotNull(stats.get("byEngineer"));
        assertNotNull(stats.get("byType"));
    }

    // ==================== 售后成本归集 ====================

    @Test
    void materialCostAccumulates() {
        Map<String, Object> o = service.register(payload(CUST, "OTHER", "NORMAL", "成本归集"));
        String orderNo = String.valueOf(o.get("orderNo"));
        service.addMaterialCost(orderNo, new BigDecimal("120.50"));
        service.addMaterialCost(orderNo, new BigDecimal("79.50"));
        Map<String, Object> d = service.detail(String.valueOf(o.get("id")));
        assertEquals(0, new BigDecimal("200.00")
                .compareTo(new BigDecimal(String.valueOf(d.get("materialCost")))),
                "服务成本按领料过账累加");
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.addMaterialCost("SO-NOPE", BigDecimal.ONE));
        assertEquals(422, e.getCode());
    }

    // ================================================================
    // 造数 / 清理
    // ================================================================

    /** 走到「待客户确认」的工单：登记→派单→受理→根因→提交确认 */
    private String readyForConfirm(String tag) {
        Map<String, Object> o = service.register(payload(CUST, "OTHER", "NORMAL", tag));
        String id = String.valueOf(o.get("id"));
        service.dispatch(id, Map.of("engineerId", "so-t-eng1", "engineerName", "服务甲"));
        authAs("so-t-eng1", "SERVICE_ENG");
        service.accept(id);
        service.saveRootCause(id, Map.of("rootCause", "EXTERNAL"));
        service.submitConfirm(id);
        authAs("so-t-cs", "CS");
        return id;
    }

    private Map<String, Object> payload(String customerId, String type, String impact, String desc) {
        return Map.of("customerId", customerId, "customerName", "SO 测试客户",
                "complaintType", type, "impactLevel", impact, "description", desc);
    }

    private void seedCustomer(String id, String name) {
        // 口径对齐 SD 域：工单 customerId = 客户组主数据 id（erp_mdm_customer_group.ID）
        jdbc.update("INSERT INTO erp_mdm_customer_group (ID, CUSTOMER_CODE, CUSTOMER_NAME, "
                + "STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, ?, '1', 'junit', '0', 0)",
                id, "CUST-" + id.toUpperCase(), name);
    }

    private void seedEngineer(String user, String nick) {
        jdbc.update("INSERT INTO erp_admin_user (ID, USERNAME, PASSWORD, NICK_NAME, STATUS, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, 'x', ?, '1', 'junit', '0', 0)",
                user, user, nick);
        jdbc.update("INSERT INTO erp_admin_user_role (ID, USER_ID, ROLE_ID, CREATE_DATE) "
                + "VALUES (?, ?, 'role-service-eng', NOW())", "ur-" + user, user);
    }

    private void seedNcr(String id, String ncrNo) {
        jdbc.update("INSERT INTO erp_qms_ncr (ID, NCR_NO, ITEM_CODE, QTY, SEVERITY, CTQ_FLAG, "
                + "REGULATORY_FLAG, FREEZE_SCOPE, FROZEN_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, 'SO-ITEM', 1, 'Major', '0', '0', 'NONE', '0', 'junit', NOW(), '0', 0)",
                id, ncrNo);
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_pm_service_process_record WHERE ORDER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_service_order WHERE CUSTOMER_ID LIKE 'so-%') t)");
        jdbc.update("DELETE FROM erp_pm_service_confirm_token WHERE ORDER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_service_order WHERE CUSTOMER_ID LIKE 'so-%') t)");
        jdbc.update("DELETE FROM erp_pm_service_sla_log WHERE ORDER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_service_order WHERE CUSTOMER_ID LIKE 'so-%') t)");
        jdbc.update("DELETE FROM erp_pm_service_survey WHERE ORDER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_service_order WHERE CUSTOMER_ID LIKE 'so-%') t)");
        jdbc.update("DELETE FROM erp_pm_service_improve_task WHERE ORDER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_service_order WHERE CUSTOMER_ID LIKE 'so-%') t)");
        jdbc.update("DELETE FROM erp_pm_service_order WHERE CUSTOMER_ID LIKE 'so-%'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'PmService%'");
        jdbc.update("DELETE FROM erp_qms_ncr WHERE ID LIKE 'so-ncr-%'");
        jdbc.update("DELETE FROM erp_admin_user_role WHERE USER_ID LIKE 'so-t-%'");
        jdbc.update("DELETE FROM erp_admin_user WHERE ID LIKE 'so-t-%'");
        jdbc.update("DELETE FROM erp_mdm_customer_group WHERE ID LIKE 'so-%'");
    }

    private void authAs(String user, String... roles) {
        getContext().setAuthentication(new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken(user, "x",
                java.util.Arrays.stream(roles)
                        .map(r -> new org.springframework.security.core.authority
                                .SimpleGrantedAuthority("ROLE_" + r))
                        .toList()));
    }
}
