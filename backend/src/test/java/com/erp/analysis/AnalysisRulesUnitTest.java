package com.erp.analysis;

import com.erp.common.ServiceException;
import com.erp.dao.bi.BiConfigDao;
import com.erp.dao.bi.BiCostSnapshotDao;
import com.erp.dao.bi.BiExportTaskDao;
import com.erp.dao.bi.BiMetricDictDao;
import com.erp.dao.bi.BiPermRuleDao;
import com.erp.dao.bi.BiPriceAlertDao;
import com.erp.dao.bi.BiExtractTaskDao;
import com.erp.dao.scm.ScmScorecardAppealDao;
import com.erp.dao.scm.ScmScorecardModelDao;
import com.erp.dao.scm.ScmScorecardRectifyDao;
import com.erp.dao.scm.ScmScorecardResultDao;
import com.erp.dao.scm.ScorecardCollectDao;
import com.erp.entity.bi.BiConfig;
import com.erp.entity.bi.BiExportTask;
import com.erp.entity.bi.BiMetricDict;
import com.erp.entity.bi.BiPermRule;
import com.erp.entity.bi.BiPriceAlert;
import com.erp.entity.scm.ScmScorecardRectify;
import com.erp.entity.scm.ScmScorecardResult;
import com.erp.security.IntfGuard;
import com.erp.service.bi.MetricGuard;
import com.erp.service.bi.QueryGovernance;
import com.erp.service.impl.bi.CostAnalysisServiceImpl;
import com.erp.service.bi.ExtractService;
import com.erp.service.bi.MetricDictService;
import com.erp.service.bi.PriceMonitorService;
import com.erp.service.impl.bi.MetricDictServiceImpl;
import com.erp.service.impl.bi.ExportServiceImpl;
import com.erp.service.impl.scm.ScorecardServiceImpl;
import com.erp.service.bi.MetricGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 采购分析规则单测（spec bi-metric-dictionary / bi-query-governance /
 * procurement-cost-analysis / supplier-scorecard / price-monitoring，tasks 9.1）。
 * 纯 mock 单测，与 PortalRulesUnitTest 同范式。
 */
class AnalysisRulesUnitTest {

    @BeforeEach
    void reset() {
        SecurityContextHolder.clearContext();
    }

    private void asUser(String name, String... roles) {
        List<SimpleGrantedAuthority> as = new ArrayList<>();
        for (String r : roles) {
            as.add(new SimpleGrantedAuthority("ROLE_" + r));
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(name, "n/a", as));
    }

    private BiMetricDict dict(String key, String formula) {
        BiMetricDict d = new BiMetricDict();
        d.setId("d1");
        d.setMetricKey(key);
        d.setFormula(formula);
        d.setStatus(BiMetricDict.ST_PUBLISHED);
        d.setVersion(1);
        d.setIsCurrent(true);
        d.setUsedBy("[{\"id\":\"page-a\",\"status\":\"ACTIVE\"}]");
        return d;
    }

    // ------------------------------------------------bi-metric-dictionary

    @Test
    void dictPublishDiffInconsistentBlocked() {
        BiMetricDictDao dao = mock(BiMetricDictDao.class);
        when(dao.selectCurrent("M1")).thenReturn(dict("M1", "{\"expr\":\"SUM(a)\"}"));
        MetricDictServiceImpl svc = new MetricDictServiceImpl(dao);

        // 一致 → 通过
        Map<String, Object> ok = svc.publish("M1", "{\"expr\":\"SUM(a)\"}");
        assertEquals("PUBLISHED", ok.get("status"));
        // 不一致 → 422（C-4.10-01 L1 硬阻断）+ 差异报告
        ServiceException e = assertThrows(ServiceException.class,
                () -> svc.publish("M1", "{\"expr\":\"AVG(b)\"}"));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("差异"));
        // 缺失字段也算不一致
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> svc.publish("M1", "{\"expr\":\"SUM(a)\",\"extra\":1}"));
        assertEquals(422, e2.getCode());
    }

    @Test
    void dictRegisterRequiresSevenElements() {
        BiMetricDictDao dao = mock(BiMetricDictDao.class);
        MetricDictServiceImpl svc = new MetricDictServiceImpl(dao);
        ServiceException e = assertThrows(ServiceException.class, () -> svc.register(Map.of(
                "metricKey", "M2", "name", "缺定义")));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("definition"));
    }

    @Test
    void formulaChangeMarksConsumersPendingUpdate() {
        BiMetricDictDao dao = mock(BiMetricDictDao.class);
        when(dao.selectCurrent("M1")).thenReturn(dict("M1", "{\"expr\":\"SUM(a)\"}"));
        when(dao.selectMaxVersion("M1")).thenReturn(1);
        when(dao.insert(any())).thenAnswer(inv -> 1);
        MetricDictServiceImpl svc = new MetricDictServiceImpl(dao);

        Map<String, Object> out = svc.changeFormula("M1", "{\"expr\":\"SUM(b)\"}", "口径调整");
        assertEquals(2, out.get("version"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> consumers = (List<Map<String, Object>>) out.get("consumers");
        assertEquals(1, consumers.size());
        // 反向扫描：引用方状态全部置 PENDING_UPDATE
        assertEquals("PENDING_UPDATE", consumers.get(0).get("status"));
    }

    @Test
    void unregisteredMetricGuard422() {
        BiMetricDictDao dao = mock(BiMetricDictDao.class);
        when(dao.selectCurrent("NOPE")).thenReturn(null);
        MetricGuard guard = new MetricGuard(new MetricDictServiceImpl(dao));
        ServiceException e = assertThrows(ServiceException.class, () -> guard.require("NOPE"));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("未注册"));
    }

    // ------------------------------------------------bi-query-governance

    @Test
    void rowLevelUnboundReturnsEmptySetNotAll() {
        BiPermRuleDao perm = mock(BiPermRuleDao.class);
        BiExportTaskDao export = mock(BiExportTaskDao.class);
        when(perm.selectRowBindings("nobody")).thenReturn(List.of());
        QueryGovernance gov = new QueryGovernance(perm, export);
        List<String> allow = gov.allowedEntities("nobody");
        assertNotNull(allow);
        assertTrue(allow.isEmpty(), "未绑定法人主体必须返回空集（默认拒绝），不得放开");
        // 通配绑定 → null 全量
        BiPermRule star = new BiPermRule();
        star.setEntityId("*");
        when(perm.selectRowBindings("admin")).thenReturn(List.of(star));
        assertNull(gov.allowedEntities("admin"));
        // 具体绑定
        BiPermRule le = new BiPermRule();
        le.setEntityId("LE-01");
        when(perm.selectRowBindings("u1")).thenReturn(List.of(le));
        assertEquals(List.of("LE-01"), gov.allowedEntities("u1"));
    }

    @Test
    void sensitiveColumnDefaultMaskedWhenRuleUnhit() {
        BiPermRuleDao perm = mock(BiPermRuleDao.class);
        BiExportTaskDao export = mock(BiExportTaskDao.class);
        BiPermRule sens = new BiPermRule();
        sens.setRuleType("COL_SENSITIVE");
        sens.setTargetKey("bank_card");
        when(perm.selectColRules()).thenReturn(List.of(sens));
        QueryGovernance gov = new QueryGovernance(perm, export);
        // 命中敏感登记但无掩码规则 → 默认掩码（C-4.10-02）
        assertEquals("6***9", gov.maskIfHit("bank_card", "6222000000000009"));
        // 未登记字段不掩码
        assertEquals("RM0001", gov.maskIfHit("itemCode", "RM0001"));
    }

    // ------------------------------------------------导出 C-4.10-05

    @Test
    void overLimitExportQueuedPendingApproval() {
        BiExportTaskDao export = mock(BiExportTaskDao.class);
        BiCostSnapshotDao snap = mock(BiCostSnapshotDao.class);
        BiPriceAlertDao alert = mock(BiPriceAlertDao.class);
        when(export.countActiveExports(anyString())).thenReturn(0);
        when(export.insert(any())).thenAnswer(inv -> 1);
        ExportServiceImpl svc = new ExportServiceImpl(export, snap, alert, mock(com.erp.service.inv.InvReportService.class));
        ReflectionTestUtils.setField(svc, "exportRowLimit", 10000L);
        ReflectionTestUtils.setField(svc, "exportParallelMax", 3);
        asUser("u1", "PM");

        Map<String, Object> over = svc.submit("cost", Map.of(), 150000L);
        assertEquals("PENDING", over.get("approvalStatus"), ">1 万行转超量审批（C-4.10-05）");
        assertTrue((Boolean) over.get("overLimit"));

        Map<String, Object> normal = svc.submit("cost", Map.of(), 500L);
        assertNull(normal.get("approvalStatus"), "未超量直接入队不需审批");
    }

    @Test
    void parallelExportLimited() {
        BiExportTaskDao export = mock(BiExportTaskDao.class);
        BiCostSnapshotDao snap = mock(BiCostSnapshotDao.class);
        BiPriceAlertDao alert = mock(BiPriceAlertDao.class);
        when(export.countActiveExports("u1")).thenReturn(3);
        ExportServiceImpl svc = new ExportServiceImpl(export, snap, alert, mock(com.erp.service.inv.InvReportService.class));
        ReflectionTestUtils.setField(svc, "exportRowLimit", 10000L);
        ReflectionTestUtils.setField(svc, "exportParallelMax", 3);
        asUser("u1", "PM");
        ServiceException e = assertThrows(ServiceException.class,
                () -> svc.submit("cost", Map.of(), 10L));
        assertEquals(422, e.getCode());
    }

    // ------------------------------------------------supplier-scorecard

    private ScorecardServiceImpl scorecard(ScmScorecardModelDao modelDao,
                                           ScmScorecardResultDao resultDao,
                                           ScmScorecardRectifyDao rectifyDao,
                                           ScmScorecardAppealDao appealDao,
                                           ScorecardCollectDao collectDao,
                                           BiMetricDictDao dictDao) {
        MetricDictServiceImpl dictSvc = new MetricDictServiceImpl(dictDao);
        MetricGuard guard = new MetricGuard(dictSvc);
        return new ScorecardServiceImpl(modelDao, resultDao, rectifyDao, appealDao,
                collectDao, guard);
    }

    @Test
    void modelWeightNot100Blocked() {
        BiMetricDictDao dictDao = mock(BiMetricDictDao.class);
        when(dictDao.selectCurrent(anyString())).thenAnswer(inv -> {
            Object k = inv.getArgument(0);
            return dict(String.valueOf(k), "{\"expr\":\"SUM(x)\"}");
        });
        ScmScorecardModelDao modelDao = mock(ScmScorecardModelDao.class);
        when(modelDao.insert(any())).thenAnswer(inv -> {
            com.erp.entity.scm.ScmScorecardModel m = inv.getArgument(0);
            m.setId("model-1");
            return 1;
        });
        ScorecardServiceImpl svc = scorecard(modelDao,
                mock(ScmScorecardResultDao.class), mock(ScmScorecardRectifyDao.class),
                mock(ScmScorecardAppealDao.class), mock(ScorecardCollectDao.class), dictDao);
        ServiceException e = assertThrows(ServiceException.class, () -> svc.saveModel(Map.of(
                "name", "m", "wQuality", 30, "wDelivery", 30, "wCost", 20, "wResponse", 19)));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("100"));
        // 合计 100 → 通过
        assertDoesNotThrow(() -> svc.saveModel(Map.of(
                "name", "m2", "wQuality", 30, "wDelivery", 30, "wCost", 20, "wResponse", 20)));
    }

    @Test
    void publishWithoutReviewBlocked() {
        BiMetricDictDao dictDao = mock(BiMetricDictDao.class);
        ScmScorecardResultDao resultDao = mock(ScmScorecardResultDao.class);
        ScmScorecardResult r = new ScmScorecardResult();
        r.setId("r1");
        r.setStatus(ScmScorecardResult.ST_GENERATED);
        when(resultDao.selectById("r1")).thenReturn(r);
        ScorecardServiceImpl svc = scorecard(mock(ScmScorecardModelDao.class), resultDao,
                mock(ScmScorecardRectifyDao.class), mock(ScmScorecardAppealDao.class),
                mock(ScorecardCollectDao.class), dictDao);
        asUser("admin", "ADMIN");
        ServiceException e = assertThrows(ServiceException.class, () -> svc.publish("r1"));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("未审核"), "C-4.9-09 未审核公示必须硬阻断");
    }

    @Test
    void frozenSupplierBlockedFromNewPo() {
        BiMetricDictDao dictDao = mock(BiMetricDictDao.class);
        ScmScorecardRectifyDao rectifyDao = mock(ScmScorecardRectifyDao.class);
        when(rectifyDao.countFrozen("sup-d")).thenReturn(1);
        ScmScorecardRectify rect = new ScmScorecardRectify();
        rect.setRectifyNo("SR20261007000001");
        when(rectifyDao.selectFrozenOpen("sup-d")).thenReturn(rect);
        ScorecardServiceImpl svc = scorecard(mock(ScmScorecardModelDao.class),
                mock(ScmScorecardResultDao.class), rectifyDao,
                mock(ScmScorecardAppealDao.class), mock(ScorecardCollectDao.class), dictDao);

        ServiceException e = assertThrows(ServiceException.class, () -> svc.assertNotFrozen("sup-d"));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("BR-4.9-05"));
        // 未冻结供应商放行
        when(rectifyDao.countFrozen("sup-ok")).thenReturn(0);
        assertDoesNotThrow(() -> svc.assertNotFrozen("sup-ok"));
    }

    @Test
    void unfreezeRequiresDifferentApprover() {
        BiMetricDictDao dictDao = mock(BiMetricDictDao.class);
        ScmScorecardRectifyDao rectifyDao = mock(ScmScorecardRectifyDao.class);
        ScmScorecardRectify rect = new ScmScorecardRectify();
        rect.setId("rc1");
        rect.setFrozen(true);
        rect.setStatus(ScmScorecardRectify.ST_CLOSED);
        rect.setCloseBy("manager-a");
        when(rectifyDao.selectById("rc1")).thenReturn(rect);
        ScorecardServiceImpl svc = scorecard(mock(ScmScorecardModelDao.class),
                mock(ScmScorecardResultDao.class), rectifyDao,
                mock(ScmScorecardAppealDao.class), mock(ScorecardCollectDao.class), dictDao);

        // 同人 → 双人复核阻断
        asUser("manager-a", "ADMIN");
        ServiceException e = assertThrows(ServiceException.class, () -> svc.unfreeze("rc1"));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("不得与整改关闭人相同"));
        // 他人 → 通过
        asUser("manager-b", "ADMIN");
        Map<String, Object> out = svc.unfreeze("rc1");
        assertEquals(false, out.get("frozen"));
        // 未关闭 → 阻断
        rect.setStatus(ScmScorecardRectify.ST_OPEN);
        asUser("manager-b", "ADMIN");
        assertEquals(422, assertThrows(ServiceException.class, () -> svc.unfreeze("rc1")).getCode());
    }

    // ------------------------------------------------price-monitoring

    @Test
    void handledAlertNotEditableAndThresholdKeepsHistory() {
        BiPriceAlertDao alertDao = mock(BiPriceAlertDao.class);
        BiConfigDao configDao = mock(BiConfigDao.class);
        com.erp.dao.bi.AgreementPriceDao agree = mock(com.erp.dao.bi.AgreementPriceDao.class);
        com.erp.dao.bi.QuoteAnomalyDao quote = mock(com.erp.dao.bi.QuoteAnomalyDao.class);
        com.erp.service.impl.bi.PriceMonitorServiceImpl svc =
                new com.erp.service.impl.bi.PriceMonitorServiceImpl(alertDao, configDao, agree, quote);

        // 已处置 → 再处置 422（留痕不可篡改）
        BiPriceAlert a = new BiPriceAlert();
        a.setId("a1");
        a.setStatus(BiPriceAlert.ST_HANDLED);
        a.setHandleBy("buyer-x");
        a.setHandleNote("已与供应商谈妥");
        when(alertDao.selectById("a1")).thenReturn(a);
        asUser("buyer-y", "PM");
        ServiceException e = assertThrows(ServiceException.class,
                () -> svc.handle("a1", "IGNORED", null));
        assertEquals(422, e.getCode());
        // 处置备注必填
        a.setStatus(BiPriceAlert.ST_OPEN);
        assertEquals(400, assertThrows(ServiceException.class,
                () -> svc.handle("a1", "HANDLED", null)).getCode());

        // 阈值调整留痕 old/new/by
        BiConfig cfg = new BiConfig();
        cfg.setId("c1");
        cfg.setConfigKey("PRICE_TREND_ALERT_PCT");
        cfg.setConfigValue("10");
        cfg.setHistoryJson("[]");
        when(configDao.selectByKey("PRICE_TREND_ALERT_PCT")).thenReturn(cfg);
        when(configDao.insert(any())).thenAnswer(inv -> 1);
        asUser("admin", "ADMIN");
        Map<String, Object> out = svc.setThreshold("PRICE_TREND_ALERT_PCT", "15", "行情波动加大");
        assertEquals("15", out.get("value"));
        assertEquals("10", out.get("old"));
        assertEquals("admin", out.get("by"));
        assertTrue(String.valueOf(out.get("note")).contains("不追溯"));
        // 在线读取新值
        assertEquals(new BigDecimal("15"),
                svc.thresholdValue("PRICE_TREND_ALERT_PCT", BigDecimal.TEN));
        // 非数值拒绝
        assertEquals(400, assertThrows(ServiceException.class,
                () -> svc.setThreshold("K", "abc", "x")).getCode());
    }
}
