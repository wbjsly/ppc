package com.erp.fin;

import com.erp.common.ServiceException;
import com.erp.entity.fin.TpAssessment;
import com.erp.entity.fin.TpDocument;
import com.erp.entity.fin.TpParty;
import com.erp.service.SysParamService;
import com.erp.service.fin.TransferPricingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关联定价 DbTest（transfer-pricing，S-4.14-08）。
 * 造数前缀 tpr-，purge 全量清理。
 */
@SpringBootTest
class TransferPricingDbTest {

    @Autowired
    private TransferPricingService service;
    @Autowired
    private SysParamService sysParamService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        sysParamService.setValue("PREF_EXPIRE_WARNING_DAYS", "90", "NUMBER", "TAX", "junit", "junit");
        loginRole("ROLE_FINANCE");
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_fin_tp_assessment WHERE ID LIKE 'tpr-%' OR ASSESS_NO LIKE 'TPA%'");
        jdbc.update("DELETE FROM erp_fin_tp_document WHERE ID LIKE 'tpr-%' OR DOC_NO LIKE 'TPD%'");
        jdbc.update("DELETE FROM erp_fin_tp_party WHERE ID LIKE 'tpr-%' OR TP_NO LIKE 'TPP%'");
    }

    private void loginRole(String role) {
        var auth = new UsernamePasswordAuthenticationToken("junit", null,
                AuthorityUtils.createAuthorityList(role));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private TpParty seedParty(String suffix, boolean overseas) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", "关联方-" + suffix);
        p.put("taxNo", "91TAX" + suffix.toUpperCase());
        p.put("relType", "COMMON_CONTROL");
        p.put("overseas", overseas);
        p.put("note", "同受控制测试");
        return service.registerParty(p);
    }

    // ---------- 关联方 ----------

    @Test
    void partyRegisterAndDuplicateBlocked() {
        TpParty p = seedParty("a", true);
        assertNotNull(p.getTpNo());
        assertTrue("1".equals(p.getOverseasFlag()), "境外标注");

        Map<String, Object> dup = new LinkedHashMap<>();
        dup.put("name", "关联方-A");
        dup.put("taxNo", "91TAXA");
        dup.put("relType", "COMMON_CONTROL");
        ServiceException e = assertThrows(ServiceException.class, () -> service.registerParty(dup));
        assertTrue(e.getMessage().contains("重复关联方"), e.getMessage());
    }

    // ---------- 定价文档 ----------

    @Test
    void documentRegisterAndMethodValidation() {
        TpParty p = seedParty("d", false);
        // 方法五族校验 422
        Map<String, Object> bad = docPayload(p.getId(), "LOCAL", "2026", "协商价",
                LocalDate.now().plusDays(300).toString());
        ServiceException e = assertThrows(ServiceException.class, () -> service.registerDocument(bad));
        assertTrue(e.getMessage().contains("独立交易原则"), e.getMessage());

        // 正常登记（TNMM）
        TpDocument d = service.registerDocument(docPayload(p.getId(), "LOCAL", "2026", "TNMM",
                LocalDate.now().plusDays(300).toString()));
        assertNotNull(d.getDocNo());
        assertEquals(TpDocument.ST_READY, d.getStatus());

        // 三档齐全（MASTER + COUNTRY）
        service.registerDocument(docPayload(p.getId(), "MASTER", "2026", "CPM",
                LocalDate.now().plusDays(300).toString()));
        service.registerDocument(docPayload(p.getId(), "COUNTRY", "2026", "CUP",
                LocalDate.now().plusDays(300).toString()));
        List<TpDocument> docs = service.listDocuments(p.getId(), "2026");
        assertEquals(3, docs.size(), "三档文档");
    }

    // ---------- 提醒 ----------

    @Test
    void expiringReminderAndYearGap() {
        TpParty p = seedParty("r", false);
        // 60 天后到期 → 临期（≤90 天）
        service.registerDocument(docPayload(p.getId(), "LOCAL", "2026", "TNMM",
                LocalDate.now().plusDays(60).toString()));
        Map<String, Object> rem = service.reminders();
        assertTrue(((Number) rem.get("expiringCount")).intValue() >= 1, "备查临期预警");

        // 年度断档：有 2026 文档，当前年（2026）——用覆盖年 2025 造断档
        TpParty p2 = seedParty("g", false);
        service.registerDocument(docPayload(p2.getId(), "LOCAL", "2025", "TNMM",
                LocalDate.now().plusDays(300).toString()));
        Map<String, Object> rem2 = service.reminders();
        assertTrue(((Number) rem2.get("yearGapCount")).intValue() >= 1, "年度断档提示");
    }

    // ---------- 风险自评 ----------

    @Test
    void assessmentSubmission() {
        // 无异常自评
        Map<String, Object> clean = new LinkedHashMap<>();
        clean.put("year", "2026");
        clean.put("riskItems", List.of("NONE"));
        TpAssessment a = service.submitAssessment(clean);
        assertEquals(TpAssessment.CONCLUSION_CLEAN, a.getConclusion());

        // 同年度重复 422
        assertThrows(ServiceException.class, () -> service.submitAssessment(clean));

        // 勾选风险项 → 有待改进
        Map<String, Object> improve = new LinkedHashMap<>();
        improve.put("year", "2027");
        improve.put("riskItems", List.of("DOCUMENTS_INCOMPLETE"));
        TpAssessment b = service.submitAssessment(improve);
        assertEquals(TpAssessment.CONCLUSION_IMPROVE, b.getConclusion());
        assertTrue(b.getRiskItemsJson().contains("DOCUMENTS_INCOMPLETE"), "风险项留痕");

        // 非法风险项 422
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("year", "2028");
        bad.put("riskItems", List.of("NOT_A_RISK"));
        assertThrows(ServiceException.class, () -> service.submitAssessment(bad));

        List<TpAssessment> all = service.listAssessments();
        assertEquals(2, all.size());
    }

    private Map<String, Object> docPayload(String partyId, String docType, String year,
                                           String method, String validUntil) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("partyId", partyId);
        d.put("docType", docType);
        d.put("coverYear", year);
        d.put("pricingMethod", method);
        d.put("storagePath", "/docs/tp/" + year);
        d.put("validUntil", validUntil);
        return d;
    }
}
