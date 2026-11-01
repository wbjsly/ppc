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
import com.erp.service.bi.MetricLineageService;
import com.erp.service.bi.QueryGovernance;
import com.erp.service.approval.ApprovalEngine;
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
import org.springframework.jdbc.core.JdbcTemplate;
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

    /** 新四参构造（add-metric-dict-console：dictDao + 审批 + 血缘 + jdbc） */
    private MetricDictServiceImpl svc(BiMetricDictDao dao) {
        return new MetricDictServiceImpl(dao, mock(ApprovalEngine.class),
                mock(MetricLineageService.class), mock(JdbcTemplate.class));
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
        MetricDictServiceImpl svc = svc(dao);

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
        MetricDictServiceImpl svc = svc(dao);
        ServiceException e = assertThrows(ServiceException.class, () -> svc.register(Map.of(
                "metricKey", "M2", "name", "缺定义")));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("definition"));
    }

    @Test
    void governanceApprovalMarksConsumersPendingUpdate() {
        // 变更链（add-metric-dict-console）：治理单签通过 → GRAYING + 引用方 PENDING_UPDATE
        BiMetricDictDao dao = mock(BiMetricDictDao.class);
        BiMetricDict curr = dict("M1", "{\"expr\":\"SUM(a)\"}");
        BiMetricDict fresh = dict("M1", "{\"expr\":\"SUM(b)\"}");
        fresh.setId("row-2");
        fresh.setVersion(2);
        fresh.setIsCurrent(false);
        fresh.setStatus(BiMetricDict.ST_CHANGE_PENDING);
        when(dao.selectById("row-2")).thenReturn(fresh);
        when(dao.selectCurrent("M1")).thenReturn(curr);

        MetricDictServiceImpl svc = svc(dao);
        svc.onGovernanceApproved("row-2");
        assertEquals(BiMetricDict.ST_GRAYING, fresh.getStatus());
        // 反向扫描：引用方状态全部置 PENDING_UPDATE（存于生效行）
        assertTrue(curr.getUsedBy().contains("PENDING_UPDATE"),
                "引用方应被标记待更新：" + curr.getUsedBy());
    }

    @Test
    void unregisteredMetricGuard422() {
        BiMetricDictDao dao = mock(BiMetricDictDao.class);
        when(dao.selectCurrent("NOPE")).thenReturn(null);
        MetricGuard guard = new MetricGuard(svc(dao));
        ServiceException e = assertThrows(ServiceException.class, () -> guard.require("NOPE"));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("未注册"));
    }

    // ------------------------------------------------bi-query-governance

    @Test
    void rowLevelUnboundReturnsEmptySetNotAll() {
        BiPermRuleDao perm = mock(BiPermRuleDao.class);
        BiExportTaskDao export = mock(BiExportTaskDao.class);
        // add-perm-control-console：allowedEntities 委托 RowScopeResolver，
        // 本测锁定 governance 层语义契约（空集=拒绝 / null=全量 / 清单=过滤）
        com.erp.service.bi.RowScopeResolver resolver =
                mock(com.erp.service.bi.RowScopeResolver.class);
        com.erp.service.bi.TempGrantService grants =
                mock(com.erp.service.bi.TempGrantService.class);
        when(resolver.resolveEntities("nobody")).thenReturn(List.of());
        when(resolver.resolveEntities("admin")).thenReturn(null);
        when(resolver.resolveEntities("u1")).thenReturn(List.of("LE-01"));
        QueryGovernance gov = new QueryGovernance(perm, export, resolver, grants);
        List<String> allow = gov.allowedEntities("nobody");
        assertNotNull(allow);
        assertTrue(allow.isEmpty(), "未绑定法人主体必须返回空集（默认拒绝），不得放开");
        assertNull(gov.allowedEntities("admin"), "通配绑定 → null 全量");
        assertEquals(List.of("LE-01"), gov.allowedEntities("u1"), "具体绑定过滤");
    }

    @Test
    void sensitiveColumnDefaultMaskedWhenRuleUnhit() {
        BiPermRuleDao perm = mock(BiPermRuleDao.class);
        BiExportTaskDao export = mock(BiExportTaskDao.class);
        BiPermRule sens = new BiPermRule();
        sens.setRuleType("COL_SENSITIVE");
        sens.setTargetKey("bank_card");
        when(perm.selectColRules()).thenReturn(List.of(sens));
        com.erp.service.bi.RowScopeResolver resolver =
                mock(com.erp.service.bi.RowScopeResolver.class);
        com.erp.service.bi.TempGrantService grants =
                mock(com.erp.service.bi.TempGrantService.class);
        when(grants.hasActive(any(), any())).thenReturn(false);
        QueryGovernance gov = new QueryGovernance(perm, export, resolver, grants);
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
        // ASSIGN_UUID 只在真实 insert 时赋值——mock 须回填 ID（否则引擎 submit 收 null bizId）
        when(export.insert(any())).thenAnswer(inv -> {
            ((BiExportTask) inv.getArgument(0)).setId("mock-task-1");
            return 1;
        });
        ExportServiceImpl svc = new ExportServiceImpl(export, snap, alert,
                mock(com.erp.service.inv.InvReportService.class),
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(com.erp.service.approval.ApprovalEngine.class),
                mock(org.springframework.jdbc.core.JdbcTemplate.class));
        ReflectionTestUtils.setField(svc, "exportRowLimit", 10000L);
        ReflectionTestUtils.setField(svc, "exportParallelMax", 3);
        // add-export-console：超量走审批引擎，mock submit 返回实例（否则 inst.getId() NPE）
        com.erp.service.approval.ApprovalEngine mockEngine =
                mock(com.erp.service.approval.ApprovalEngine.class);
        com.erp.entity.system.ApprovalInstance mockInst =
                new com.erp.entity.system.ApprovalInstance();
        mockInst.setId("mock-appr-1");
        when(mockEngine.submit(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(mockInst);
        ReflectionTestUtils.setField(svc, "approvalEngine", mockEngine);
        asUser("u1", "PM");

        // add-export-console：超量须用途 ≥10 字（FR-4.10-4-2），旧用例补 purpose
        Map<String, Object> over = svc.submit("cost",
                Map.of("purpose", "月度成本分析导出用途说明"), 150000L);
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
        ExportServiceImpl svc = new ExportServiceImpl(export, snap, alert,
                mock(com.erp.service.inv.InvReportService.class),
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(com.erp.service.approval.ApprovalEngine.class),
                mock(org.springframework.jdbc.core.JdbcTemplate.class));
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
        MetricDictServiceImpl dictSvc = svc(dictDao);
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
