package com.erp.sd;

import com.erp.common.ServiceException;
import com.erp.dao.crm.LeadDao;
import com.erp.dao.crm.LeadFollowupDao;
import com.erp.dao.crm.LeadPoolDao;
import com.erp.dao.crm.LeadScoreModelDao;
import com.erp.dao.mdm.MdmCustomerGroupDao;
import com.erp.dao.mdm.MdmCustomerViewDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.mdm.MdmPriceAgreementDao;
import com.erp.dao.mdm.MdmPriceAgreementLineDao;
import com.erp.dao.ops.MdmOutboxDao;
import com.erp.dao.sd.DiscountChannelDao;
import com.erp.dao.sd.PriceAuditDao;
import com.erp.dao.sd.PromotionDao;
import com.erp.dao.sd.QuoteLineDao;
import com.erp.dao.sd.QuoteVersionDao;
import com.erp.dao.sd.SdQuoteDao;
import com.erp.dao.sd.SpecialPriceDao;
import com.erp.entity.crm.Lead;
import com.erp.entity.crm.LeadScoreModel;
import com.erp.entity.mdm.MdmCustomerView;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mdm.MdmPriceAgreement;
import com.erp.entity.mdm.MdmPriceAgreementLine;
import com.erp.entity.sd.DiscountChannel;
import com.erp.entity.sd.Promotion;
import com.erp.entity.sd.SdQuote;
import com.erp.entity.sd.QuoteLine;
import com.erp.service.SysParamService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.impl.crm.LeadScoreModelServiceImpl;
import com.erp.service.impl.crm.LeadServiceImpl;
import com.erp.service.impl.mdm.MdmCrossDomainServiceImpl;
import com.erp.service.impl.sd.PricingDiscountServiceImpl;
import com.erp.service.impl.sd.QuotePublisher;
import com.erp.service.impl.sd.QuoteServiceImpl;
import com.erp.service.mdm.MdmCrossDomainService;
import com.erp.entity.system.ApprovalInstance;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 17.1 规则单测：线索评分权重与分级、三协议阶梯取价优先级、
 * 折扣最优单层与求和阻断、最低毛利阻断（spec crm-lead-management /
 * cross-domain-sharing / sales-pricing-discount / sales-quote）。
 */
class PricingScoreRulesUnitTest {

    @BeforeEach
    void auth() {
        // 全角色注入：各服务 requireAny 白名单不同，直调单测统一放行
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tester", "n/a", List.of(
                        new SimpleGrantedAuthority("ROLE_SALES"),
                        new SimpleGrantedAuthority("ROLE_SALES_MGR"),
                        new SimpleGrantedAuthority("ROLE_SALES_DIRECTOR"),
                        new SimpleGrantedAuthority("ROLE_FINANCE_MGR"),
                        new SimpleGrantedAuthority("ROLE_CREDIT_ADMIN"),
                        new SimpleGrantedAuthority("ROLE_WAREHOUSE"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    // ================= 17.1-a 评分权重与分级 =================

    private LeadScoreModelServiceImpl scoreModel;
    private LeadScoreModelDao modelDao;
    private LeadServiceImpl leadService;
    private LeadDao leadDao;
    private LeadPoolDao poolDao;
    private SysParamService paramService;

    private void initScore() {
        modelDao = mock(LeadScoreModelDao.class);
        scoreModel = new LeadScoreModelServiceImpl(modelDao);
        leadDao = mock(LeadDao.class);
        leadService = new LeadServiceImpl(leadDao, mock(LeadFollowupDao.class),
                poolDao = mock(LeadPoolDao.class), scoreModel);
    }

    private LeadScoreModel model(int... weights) {
        LeadScoreModel m = new LeadScoreModel();
        m.setModelKey("M-TEST");
        m.setModelName("测试模型");
        m.setWNeed(weights[0]);
        m.setWBudget(weights[1]);
        m.setWChain(weights[2]);
        m.setWUrgency(weights[3]);
        m.setWCompete(weights[4]);
        m.setGradeAMin(80);
        m.setGradeBMin(60);
        m.setGradeCMin(40);
        m.setVersion(1);
        return m;
    }

    @Test
    void scoreWeightsMustSum100() {
        initScore();
        LeadScoreModel bad = model(30, 20, 20, 10, 10); // = 90
        ServiceException e = assertThrows(ServiceException.class, () -> scoreModel.save(bad));
        assertTrue(e.getMessage().contains("100"), e.getMessage());
        // 恰好 100 通过
        when(modelDao.selectVersions("M-TEST")).thenReturn(List.of());
        LeadScoreModel ok = model(20, 20, 20, 20, 20);
        assertNotNull(scoreModel.save(ok));
    }

    @Test
    void gradeThresholdsMustBeDescending() {
        initScore();
        LeadScoreModel m = model(20, 20, 20, 20, 20);
        m.setGradeAMin(50);
        m.setGradeBMin(70); // A <= B 非法
        ServiceException e = assertThrows(ServiceException.class, () -> scoreModel.save(m));
        assertTrue(e.getMessage().contains("A > B > C"), e.getMessage());
    }

    @Test
    void leadScoreFormulaAndGradeBoundaries() {
        initScore();
        when(leadDao.selectById("L1")).thenReturn(new Lead());
        when(modelDao.selectActive(org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(model(20, 20, 20, 20, 20));

        // 全 5 分 = Σ(5/5×20) = 100 → A
        Map<String, Object> out = leadService.score("L1", 5, 5, 5, 5, 5);
        assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) out.get("score")));
        assertEquals("A", out.get("grade"));

        // 全 3 分 = Σ(3/5×20) = 60 → B（B 下界命中）
        out = leadService.score("L1", 3, 3, 3, 3, 3);
        assertEquals("B", out.get("grade"));

        // 全 2 分 = 40 → C；全 1 分 = 20 → D（入池）
        out = leadService.score("L1", 2, 2, 2, 2, 2);
        assertEquals("C", out.get("grade"));
        out = leadService.score("L1", 1, 1, 1, 1, 1);
        assertEquals("D", out.get("grade"));
        verify(poolDao).insert(any());
    }

    @Test
    void scoreRejectsOutOfRangeAndWarnsMissing() {
        initScore();
        when(leadDao.selectById("L1")).thenReturn(new Lead());
        when(modelDao.selectActive(org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(model(20, 20, 20, 20, 20));

        ServiceException e = assertThrows(ServiceException.class,
                () -> leadService.score("L1", 6, 5, 5, 5, 5));
        assertTrue(e.getMessage().contains("1-5"), e.getMessage());

        Map<String, Object> out = leadService.score("L1", 5, null, 5, 5, 5);
        assertTrue(((List<?>) out.get("missing")).contains("预算确认度"));
        assertNotNull(out.get("warning"));
    }

    // ================= 17.1-b 三协议取价优先级 =================

    private MdmCrossDomainServiceImpl trialService;
    private MdmPriceAgreementDao paDao;
    private MdmPriceAgreementLineDao paLineDao;

    private MdmPriceAgreement pa(String id, String code, String type) {
        MdmPriceAgreement a = new MdmPriceAgreement();
        a.setId(id);
        a.setPaCode(code);
        a.setPaName(code);
        a.setAgreementType(type);
        a.setStatus("1");
        a.setCustomerGroupId("G1");
        a.setEffectiveDate(LocalDate.of(2026, 1, 1));
        a.setExpireDate(LocalDate.of(2027, 12, 31));
        return a;
    }

    private MdmPriceAgreementLine line(String paId, BigDecimal min, BigDecimal max, BigDecimal price) {
        MdmPriceAgreementLine l = new MdmPriceAgreementLine();
        l.setPaId(paId);
        l.setItemCode("RM0001000001");
        l.setMinQty(min);
        l.setMaxQty(max);
        l.setUnitPrice(price);
        return l;
    }

    private void initTrial() {
        paDao = mock(MdmPriceAgreementDao.class);
        paLineDao = mock(MdmPriceAgreementLineDao.class);
        MdmCustomerGroupDao groupDao = mock(MdmCustomerGroupDao.class);
        trialService = new MdmCrossDomainServiceImpl(mock(MdmOutboxDao.class), paDao, paLineDao,
                mock(com.erp.dao.mdm.MdmPriceAgreementVersionDao.class), groupDao,
                mock(MdmCustomerViewDao.class), mock(MdmItemDao.class), new ObjectMapper());
        when(groupDao.selectById("G1")).thenReturn(new com.erp.entity.mdm.MdmCustomerGroup());
        // 第一次调用是 trial 内的懒过期 sweep（空=无过期），之后是候选查询
        when(paLineDao.selectList(any())).thenReturn(List.of(
                line("P-EX", null, null, new BigDecimal("97")),
                line("P-LD", BigDecimal.ONE, new BigDecimal("9999"), new BigDecimal("96"))));
    }

    @Test
    void trialPrefersExclusiveOverLadderAndTime() {
        initTrial();
        // 故意逆序放入：TIME / LADDER / EXCLUSIVE → 应选 EXCLUSIVE
        when(paDao.selectList(any())).thenReturn(new ArrayList<>(List.of(
                pa("P-TM", "PA-TM", "TIME"),
                pa("P-LD", "PA-LD", "LADDER"),
                pa("P-EX", "PA-EX", "EXCLUSIVE"))));
        Map<String, Object> hit = trialService.trial("G1", null, "RM0001000001",
                new BigDecimal("100"), null);
        assertEquals(Boolean.TRUE, hit.get("matched"));
        assertEquals("EXCLUSIVE", hit.get("agreementType"));
        assertEquals("PA-EX", hit.get("paCode"));
    }

    @Test
    void ladderMissFallsThroughToNextCandidate() {
        initTrial();
        // LADDER 无区间命中（数量 100 < 500）→ 记录原因后落到 TIME
        when(paDao.selectList(any())).thenReturn(new ArrayList<>(List.of(
                pa("P-LD", "PA-LD", "LADDER"),
                pa("P-TM", "PA-TM", "TIME"))));
        when(paLineDao.selectList(any())).thenReturn(List.of(
                line("P-LD", new BigDecimal("500"), new BigDecimal("9999"), new BigDecimal("90")),
                line("P-TM", null, null, new BigDecimal("99"))));
        Map<String, Object> hit = trialService.trial("G1", null, "RM0001000001",
                new BigDecimal("100"), null);
        assertEquals(Boolean.TRUE, hit.get("matched"));
        assertEquals("TIME", hit.get("agreementType"));
    }

    @Test
    void trialMissReturnsReasonsAndPriorityHint() {
        initTrial();
        when(paDao.selectList(any())).thenReturn(new ArrayList<>());
        Map<String, Object> miss = trialService.trial("G1", null, "RM0001000001",
                new BigDecimal("100"), null);
        assertEquals(Boolean.FALSE, miss.get("matched"));
        assertEquals("无适用协议", miss.get("message"));
        assertTrue(String.valueOf(miss.get("checkedTypes")).contains("EXCLUSIVE > LADDER > TIME"));
    }

    // ================= 17.1-c/d 折扣单层、求和阻断、最低毛利 =================

    private PricingDiscountServiceImpl pricing;
    private DiscountChannelDao channelDao;
    private PromotionDao promoDao;
    private MdmPriceAgreementDao prPaDao;
    private MdmPriceAgreementLineDao prLineDao;
    private MdmItemDao itemDao;
    private MdmCrossDomainService crossDomain;

    private void initPricing(String stackMode, BigDecimal standardCost) {
        channelDao = mock(DiscountChannelDao.class);
        promoDao = mock(PromotionDao.class);
        prPaDao = mock(MdmPriceAgreementDao.class);
        prLineDao = mock(MdmPriceAgreementLineDao.class);
        itemDao = mock(MdmItemDao.class);
        paramService = mock(SysParamService.class);
        crossDomain = mock(MdmCrossDomainService.class);
        MdmCustomerViewDao viewDao = mock(MdmCustomerViewDao.class);
        MdmCustomerGroupDao groupDao = mock(MdmCustomerGroupDao.class);

        pricing = new PricingDiscountServiceImpl(channelDao, promoDao, mock(PriceAuditDao.class),
                mock(SpecialPriceDao.class), viewDao, itemDao, prPaDao, prLineDao,
                crossDomain, groupDao, paramService, mock(ApprovalEngine.class));

        // 客户渠道
        MdmCustomerView v = new MdmCustomerView();
        v.setGroupId("G1");
        v.setChannel("KEY_ACCOUNT");
        when(viewDao.selectList(any())).thenReturn(List.of(v));
        // 渠道折扣 5%
        DiscountChannel dc = new DiscountChannel();
        dc.setChannel("KEY_ACCOUNT");
        dc.setStatus(DiscountChannel.ST_ACTIVE);
        dc.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        dc.setDiscountRate(new BigDecimal("0.05"));
        when(channelDao.selectList(any())).thenReturn(List.of(dc));
        // LADDER 协议：100 → 90（= 10% 折扣）
        when(prPaDao.selectList(any())).thenReturn(List.of(pa("P-LD2", "PA-LD2", "LADDER")));
        when(prLineDao.selectList(any())).thenReturn(List.of(
                line("P-LD2", BigDecimal.ONE, new BigDecimal("99999"), new BigDecimal("90"))));
        // 无促销
        when(promoDao.selectList(any())).thenReturn(List.of());
        // 试算非 TIME（LADDER）→ 不进时间层
        when(crossDomain.trial(any(), any(), any(), any(), any())).thenReturn(Map.of(
                "matched", false, "applicable", false));
        // 物料成本
        MdmItem item = new MdmItem();
        item.setItemCode("RM0001000001");
        item.setStandardCost(standardCost);
        when(itemDao.selectOne(any())).thenReturn(item);
        when(paramService.getValue("DISCOUNT_STACK_MODE", "BEST_SINGLE")).thenReturn(stackMode);
        when(paramService.getAmount(eq("MIN_MARGIN_RATE"), any())).thenReturn(new BigDecimal("0.05"));
    }

    @Test
    void bestSinglePicksGreatestSingleLayer() {
        initPricing("BEST_SINGLE", new BigDecimal("40"));
        // 渠道 5% + 阶梯 10% → 最优单层 10%（LADDER），finalPrice = 100×0.9 = 90
        Map<String, Object> out = pricing.calc("G1", "RM0001000001", new BigDecimal("100"),
                new BigDecimal("100"), "CALC", null, null, false);
        assertEquals("LADDER", out.get("appliedSource"));
        assertEquals(0, new BigDecimal("90.0000").compareTo((BigDecimal) out.get("finalPrice")));
        assertFalse(Boolean.TRUE.equals(out.get("blocked")), String.valueOf(out.get("blockReason")));
        Map<?, ?> margin = (Map<?, ?>) out.get("margin");
        // 阈值 = 40×1.05 = 42 ≤ 90 → 不阻断
        assertFalse(Boolean.TRUE.equals(margin.get("blocked")));
    }

    @Test
    void stackedSumOver100IsBlocked() {
        initPricing("STACKED", new BigDecimal("40"));
        // 渠道 5% + 阶梯 10% + 促销 90% = 105% > 100% → 求和叠加异常阻断
        Promotion promo = new Promotion();
        promo.setPromoNo("PR-TEST");
        promo.setItemCode("RM0001000001");
        promo.setStatus(Promotion.ST_PUBLISHED);
        promo.setStartDate(LocalDate.now().minusDays(1));
        promo.setEndDate(LocalDate.now().plusDays(1));
        promo.setDiscountRate(new BigDecimal("0.9"));
        when(promoDao.selectList(any())).thenReturn(List.of(promo));

        Map<String, Object> out = pricing.calc("G1", "RM0001000001", new BigDecimal("100"),
                new BigDecimal("100"), "CALC", null, null, false);
        assertEquals(Boolean.TRUE, out.get("blocked"));
        assertTrue(String.valueOf(out.get("blockReason")).contains("超过 100%"),
                String.valueOf(out.get("blockReason")));
        assertEquals("STACKED", out.get("stackMode"));
    }

    @Test
    void marginBelowThresholdBlocksWithoutSpecialPrice() {
        initPricing("BEST_SINGLE", new BigDecimal("120"));
        // 成本 120 → 阈值 126 > finalPrice 90，无特殊价 → margin.blocked
        Map<String, Object> out = pricing.calc("G1", "RM0001000001", new BigDecimal("100"),
                new BigDecimal("100"), "CALC", null, null, false);
        Map<?, ?> margin = (Map<?, ?>) out.get("margin");
        assertEquals(Boolean.TRUE, margin.get("blocked"));
        assertNotNull(margin.get("gap"));
        // 阈值 = 120 × 1.05 = 126
        assertEquals(0, new BigDecimal("126.0000").compareTo((BigDecimal) margin.get("threshold")));
    }

    @Test
    void missingChannelYieldsWarning() {
        initPricing("BEST_SINGLE", new BigDecimal("40"));
        // 无渠道主数据 → warning（BR-4.3-32）
        MdmCustomerViewDao viewDao = mock(MdmCustomerViewDao.class);
        pricing = new PricingDiscountServiceImpl(channelDao, promoDao, mock(PriceAuditDao.class),
                mock(SpecialPriceDao.class), viewDao, itemDao, prPaDao, prLineDao,
                crossDomain, mock(MdmCustomerGroupDao.class), paramService,
                mock(ApprovalEngine.class));
        when(viewDao.selectList(any())).thenReturn(List.of());
        Map<String, Object> out = pricing.calc("G1", "RM0001000001", new BigDecimal("100"),
                new BigDecimal("100"), "CALC", null, null, false);
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) out.get("warnings");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("渠道属性未维护")), String.valueOf(warnings));
    }

    // ================= 17.1-d 报价最低毛利阻断 =================

    @Test
    void quoteLowMarginRequiresSecondConfirm() {
        SdQuoteDao quoteDao = mock(SdQuoteDao.class);
        QuoteServiceImpl quoteService = new QuoteServiceImpl(quoteDao, mock(QuoteLineDao.class),
                mock(QuoteVersionDao.class), mock(com.erp.dao.sd.SoDao.class),
                mock(com.erp.dao.sd.SoLineDao.class),
                mock(com.erp.dao.crm.OpportunityDao.class),
                mock(com.erp.dao.crm.OppStageLogDao.class),
                mock(com.erp.dao.crm.ContractDao.class),
                mock(MdmItemDao.class), mock(MdmCustomerGroupDao.class),
                mock(com.erp.service.mdm.MdmCrossDomainService.class),
                mock(ApprovalEngine.class), paramService = mock(SysParamService.class),
                mock(QuotePublisher.class),
                mock(com.erp.service.sd.CreditControlService.class),
                mock(com.erp.service.crm.ContractService.class));
        SdQuote q = new SdQuote();
        q.setId("Q1");
        q.setDraftNo("DQ-1");
        q.setStatus(SdQuote.ST_DRAFT);
        q.setTotalAmount(new BigDecimal("60000"));
        q.setMarginRate(new BigDecimal("0.03")); // < MIN 0.05
        when(quoteDao.selectById("Q1")).thenReturn(q);
        when(paramService.getAmount(eq("MIN_MARGIN_RATE"), any())).thenReturn(new BigDecimal("0.05"));
        when(paramService.getAmount(eq("QUOTE_APPROVAL_AUTO"), any())).thenReturn(new BigDecimal("50000"));
        when(paramService.getAmount(eq("QUOTE_APPROVAL_MAJOR"), any())).thenReturn(new BigDecimal("500000"));

        QuoteServiceImpl svc1 = quoteService;
        ServiceException e = assertThrows(ServiceException.class, () -> svc1.submit("Q1"));
        assertTrue(e.getMessage().contains("二次确认"), e.getMessage());

        // 完成二次确认后可提交（低毛利不走自动档 → 进审批链）
        q.setMarginConfirmBy("salesmgr01");
        ApprovalEngine engine = mock(ApprovalEngine.class);
        ApprovalInstance inst = new ApprovalInstance();
        inst.setId("AP-1");
        when(engine.submit(anyString(), anyString(), anyString(), any(), any())).thenReturn(inst);
        quoteService = new QuoteServiceImpl(quoteDao, mock(QuoteLineDao.class),
                mock(QuoteVersionDao.class), mock(com.erp.dao.sd.SoDao.class),
                mock(com.erp.dao.sd.SoLineDao.class),
                mock(com.erp.dao.crm.OpportunityDao.class),
                mock(com.erp.dao.crm.OppStageLogDao.class),
                mock(com.erp.dao.crm.ContractDao.class),
                mock(MdmItemDao.class), mock(MdmCustomerGroupDao.class),
                mock(com.erp.service.mdm.MdmCrossDomainService.class),
                engine, paramService, mock(QuotePublisher.class),
                mock(com.erp.service.sd.CreditControlService.class),
                mock(com.erp.service.crm.ContractService.class));
        when(quoteDao.updateById(any(SdQuote.class))).thenReturn(1);
        quoteService.submit("Q1");
        ArgumentCaptor<List> chain = ArgumentCaptor.forClass(List.class);
        verify(engine).submit(eq("Quote"), eq("Q1"), anyString(), eq(null), chain.capture());
        // 低毛利（非负）→ 常规单节点销售经理链
        @SuppressWarnings("unchecked")
        List<List<ApprovalNodeSpec>> ch = chain.getValue();
        assertEquals(1, ch.size());
        assertEquals(1, ch.get(0).size());
    }
}
