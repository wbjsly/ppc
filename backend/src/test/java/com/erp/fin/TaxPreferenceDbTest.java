package com.erp.fin;

import com.erp.common.ServiceException;
import com.erp.dao.fin.TaxPreferenceDao;
import com.erp.entity.fin.TaxPreference;
import com.erp.service.SysParamService;
import com.erp.service.fin.TaxFilingService;
import com.erp.service.fin.TaxPreferenceService;
import com.erp.service.fin.TaxPreferenceValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 优惠备案 DbTest（tax-preference-register，S-4.14-11 / BR-4.14-05/06）。
 * 共享开发库：造数前缀 trp-，purge 全量清理（含 2099 期间损益余额防串扰）。
 */
@SpringBootTest
class TaxPreferenceDbTest {

    @Autowired
    private TaxPreferenceService prefService;
    @Autowired
    private TaxPreferenceValidator validator;
    @Autowired
    private TaxFilingService taxFilingService;
    @Autowired
    private SysParamService sysParamService;
    @Autowired
    private TaxPreferenceDao prefDao;
    @Autowired
    private JdbcTemplate jdbc;

    private static final LocalDate D0 = LocalDate.of(2099, 1, 1);
    private static final LocalDate D1 = LocalDate.of(2099, 12, 31);

    @BeforeEach
    void setUp() {
        purge();
        sysParamService.setValue("PREF_EXPIRE_WARNING_DAYS", "90", "NUMBER", "TAX", "junit-reset", "junit");
        loginRole("ROLE_FINANCE");
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_fin_tax_return_line WHERE RETURN_ID IN "
                + "(SELECT ID FROM erp_fin_tax_return WHERE PERIOD LIKE '2099%')");
        jdbc.update("DELETE FROM erp_fin_tax_return_adj WHERE PERIOD LIKE '2099%'");
        jdbc.update("DELETE FROM erp_fin_tax_return WHERE PERIOD LIKE '2099%'");
        jdbc.update("DELETE FROM erp_fin_balance WHERE PERIOD LIKE '2099%' AND ACCOUNT_CODE LIKE '6%'");
        jdbc.update("DELETE FROM erp_fin_tax_preference WHERE ID LIKE 'trp-%' OR PREF_NO LIKE 'TPF%'");
        jdbc.update("DELETE FROM erp_fin_tax_cert WHERE ID LIKE 'trp-%'");
        jdbc.update("DELETE FROM erp_fin_offline_invoice WHERE ID LIKE 'trp-%'");
        jdbc.update("DELETE FROM erp_fin_tp_assessment WHERE ID LIKE 'trp-%'");
        jdbc.update("DELETE FROM erp_fin_tp_document WHERE ID LIKE 'trp-%'");
        jdbc.update("DELETE FROM erp_fin_tp_party WHERE ID LIKE 'trp-%'");
    }

    private void loginRole(String role) {
        var auth = new UsernamePasswordAuthenticationToken("junit", null,
                AuthorityUtils.createAuthorityList(role));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private Map<String, Object> prefPayload(String suffix, String type, String start, String end) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("policyName", "测试优惠-" + suffix);
        p.put("policyDocNo", "财税测试[" + suffix + "]号");
        p.put("prefType", type);
        p.put("subject", "测试主体-" + suffix);
        p.put("prefStart", start);
        p.put("prefEnd", end);
        return p;
    }

    // ---------- 2.6 场景 ----------

    @Test
    void registerWithRequiredValidation() {
        // 登记成功（S-4.14-11）
        TaxPreference p = prefService.register(prefPayload("r1", TaxPreference.TYPE_VAT_REFUND,
                "2099-01-01", "2099-12-31"));
        assertNotNull(p.getPrefNo());
        assertTrue(p.getPrefNo().startsWith("TPF"));
        assertEquals(TaxPreference.ST_ACTIVE, p.getStatus());

        // 必备字段缺失 422（政策依据）
        Map<String, Object> bad = prefPayload("r2", TaxPreference.TYPE_OTHER, "2099-01-01", "2099-12-31");
        bad.remove("policyDocNo");
        ServiceException e = assertThrows(ServiceException.class, () -> prefService.register(bad));
        assertTrue(e.getMessage().contains("政策依据文号"), e.getMessage());

        // 缺优惠期间 422
        Map<String, Object> bad2 = prefPayload("r3", TaxPreference.TYPE_OTHER, null, null);
        ServiceException e2 = assertThrows(ServiceException.class, () -> prefService.register(bad2));
        assertTrue(e2.getMessage().contains("优惠期间"), e2.getMessage());
    }

    @Test
    void overlappingPeriodBlocked() {
        prefService.register(prefPayload("ov", TaxPreference.TYPE_VAT_REFUND,
                "2099-01-01", "2099-12-31"));
        // 同主体（覆盖 subject）+ 同类型 + 期间重叠 → 422
        Map<String, Object> dup = prefPayload("ov2", TaxPreference.TYPE_VAT_REFUND,
                "2099-06-01", "2100-06-30");
        dup.put("subject", "测试主体-ov");
        ServiceException e = assertThrows(ServiceException.class, () -> prefService.register(dup));
        assertTrue(e.getMessage().contains("重复备案"), e.getMessage());
    }

    @Test
    void renewExtendsWithHistory() {
        TaxPreference p = prefService.register(prefPayload("rn", TaxPreference.TYPE_RND_SUPER,
                LocalDate.now().minusDays(30).toString(), LocalDate.now().plusDays(80).toString()));
        // 临期（80 天 ≤ 90 天阈值）出现在预警清单
        Map<String, Object> w = prefService.warnings();
        assertTrue(((Number) w.get("expiringCount")).intValue() >= 1, "临期预警");

        Map<String, Object> renew = new LinkedHashMap<>();
        renew.put("newEnd", LocalDate.now().plusDays(365).toString());
        renew.put("basis", "续期备案回执");
        TaxPreference renewed = prefService.renew(p.getId(), renew);
        assertTrue(renewed.getPrefEnd().isAfter(LocalDate.now().plusDays(300)), "止日延长");
        assertNotNull(renewed.getRenewHistoryJson(), "续期留痕");
        assertTrue(renewed.getRenewHistoryJson().contains("续期备案回执"), "留痕含依据");

        // 续期后预警消除（该条不再临期）
        Map<String, Object> w2 = prefService.warnings();
        boolean stillThere = ((java.util.List<?>) w2.get("expiring")).stream()
                .anyMatch(row -> String.valueOf(((Map<?, ?>) row).get("preference"))
                        .contains(p.getPrefNo()));
        assertTrue(!stillThere, "续期后预警消除");

        // 止日不晚于原止日 422
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("newEnd", p.getPrefEnd().plusDays(1).minusDays(366).toString());
        bad.put("basis", "x");
        assertThrows(ServiceException.class, () -> prefService.renew(p.getId(), bad));
    }

    @Test
    void manualFreezeKeepsBlocked() {
        TaxPreference p = prefService.register(prefPayload("fz", TaxPreference.TYPE_RND_SUPER,
                "2099-01-01", "2099-12-31"));
        TaxPreference frozen = prefService.freeze(p.getId(), "研发人员占比下降");
        assertEquals(TaxPreference.ST_FROZEN, frozen.getStatus());
        assertTrue(frozen.getFrozenReason().contains("研发人员占比下降"), "原因留痕");

        // 冻结后申报校验 422（BR-4.14-05）
        ServiceException e = assertThrows(ServiceException.class, () ->
                validator.validateForFiling("209910", "CIT", null, null, new BigDecimal("100000")));
        assertTrue(e.getMessage().contains("BR-4.14-05"), e.getMessage());
        assertTrue(e.getMessage().contains("冻结"), e.getMessage());

        // 冻结记录入看板信号源（warnings.frozenOrTerminated）
        Map<String, Object> w = prefService.warnings();
        assertTrue(((Number) w.get("frozenOrTerminatedCount")).intValue() >= 1, "冻结入清单");
    }

    @Test
    void revokeTerminatesWithAssessment() {
        TaxPreference p = prefService.register(prefPayload("rv", TaxPreference.TYPE_VAT_REFUND,
                "2099-01-01", "2099-12-31"));
        // 缺风险评估说明 422
        assertThrows(ServiceException.class, () -> prefService.revoke(p.getId(), null, " "));
        // 正常终止
        TaxPreference t = prefService.revoke(p.getId(), "2099-06-30", "已享受退税 30 万，需评估补税及滞纳金");
        assertEquals(TaxPreference.ST_TERMINATED, t.getStatus());
        assertNotNull(t.getRevokeDate());
        assertTrue(t.getRiskAssessNote().contains("补税"), "评估留痕");
        // 终止后申报校验 422
        ServiceException e = assertThrows(ServiceException.class, () ->
                validator.validateForFiling("209910", "VAT", new BigDecimal("65000"),
                        new BigDecimal("2000000"), null));
        assertTrue(e.getMessage().contains("BR-4.14-05"), e.getMessage());
    }

    @Test
    void vatRefundScopeCapViolated422() {
        // 备案范围收入上限 250 万（PREF_SCOPE_JSON.capIncome）
        Map<String, Object> p = prefPayload("sc", TaxPreference.TYPE_VAT_REFUND,
                "2099-01-01", "2099-12-31");
        p.put("prefScopeJson", "{\"capIncome\":2500000}");
        prefService.register(p);

        // 越界 422：收入 300 万超上限 250 万 → 越界 50 万
        ServiceException e = assertThrows(ServiceException.class, () ->
                validator.validateForFiling("209910", "VAT", new BigDecimal("65000"),
                        new BigDecimal("3000000"), null));
        assertTrue(e.getMessage().contains("BR-4.14-05"), e.getMessage());
        assertTrue(e.getMessage().contains("500000"), "越界金额明细：" + e.getMessage());

        // 范围内放行
        validator.validateForFiling("209910", "VAT", new BigDecimal("65000"),
                new BigDecimal("2000000"), null);
    }

    @Test
    void rndSuperWithoutPreferenceBlockedInCitGenerate() {
        // 无备案时 CIT 含加计扣除 → generateCit 422（BR-4.14-05，注入链路集成验证）
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("citBase", "0");
        payload.put("rndSuperDeduction", "100000");
        ServiceException e = assertThrows(ServiceException.class, () ->
                taxFilingService.generateCit("209912", payload));
        assertTrue(e.getMessage().contains("BR-4.14-05"), e.getMessage());
        assertTrue(e.getMessage().contains("加计扣除"), e.getMessage());

        // 登记有效加计扣除备案（含科目口径）→ 放行生成
        Map<String, Object> pref = prefPayload("ci", TaxPreference.TYPE_RND_SUPER,
                "2099-01-01", "2099-12-31");
        pref.put("prefScopeJson", "{\"rndAccounts\":[\"660201\",\"660202\"]}");
        prefService.register(pref);
        Map<String, Object> out = taxFilingService.generateCit("209912", payload);
        assertNotNull(out.get("returnNo"), "备案有效时放行");

        // 不含优惠数据（无 rndSuperDeduction）不触发校验
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("citBase", "0");
        // 同期已有申报单 → 409（走到 409 说明未被 BR-4.14-05 拦截）
        ServiceException e2 = assertThrows(ServiceException.class, () ->
                taxFilingService.generateCit("209912", plain));
        assertTrue(e2.getMessage().contains("已存在"), e2.getMessage());
    }

    @Test
    void lazyExpireFreezesOnAccess() {
        // 止日已过（2026-03-31 < 今天）的 ACTIVE 备案：访问时惰性判定过期
        TaxPreference p = prefService.register(prefPayload("ex", TaxPreference.TYPE_VAT_REFUND,
                "2026-01-01", "2026-03-31"));
        TaxPreference d = prefService.detail(p.getId());
        assertEquals(TaxPreference.ST_EXPIRED, d.getStatus(), "惰性过期回写");
        assertNotNull(d.getFrozenReason());
        assertTrue(d.getFrozenReason().contains("系统过期"), "过期冻结原因");
    }
}
