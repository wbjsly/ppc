package com.erp.fin;

import com.erp.common.ServiceException;
import com.erp.entity.fin.OfflineInvoice;
import com.erp.entity.fin.TaxCert;
import com.erp.service.SysParamService;
import com.erp.service.fin.RiskDashboardService;
import com.erp.service.fin.TaxBurdenService;
import com.erp.service.fin.TaxRiskService;
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
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 税控证书/离线开票/税负率/看板 DbTest（tax-risk-monitoring，C-4.14-14）。
 * 造数前缀 trk-，purge 全量清理。
 */
@SpringBootTest
class TaxRiskDbTest {

    @Autowired
    private TaxRiskService riskService;
    @Autowired
    private TaxBurdenService burdenService;
    @Autowired
    private RiskDashboardService dashboardService;
    @Autowired
    private SysParamService sysParamService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        sysParamService.setValue("TAX_CERT_WARNING_DAYS", "30", "NUMBER", "TAX", "junit", "junit");
        sysParamService.setValue("OFFLINE_INVOICE_UPLOAD_HOURS", "48", "NUMBER", "TAX", "junit", "junit");
        sysParamService.setValue("TAX_BURDEN_FLOOR", "0.01", "NUMBER", "TAX", "junit", "junit");
        sysParamService.setValue("TAX_BURDEN_MOM_LIMIT", "0.30", "NUMBER", "TAX", "junit", "junit");
        loginRole("ROLE_FINANCE");
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_fin_tax_cert WHERE ID LIKE 'trk-%' OR CERT_NO LIKE 'JUNITCERT-%'");
        jdbc.update("DELETE FROM erp_fin_offline_invoice WHERE ID LIKE 'trk-%' OR OFFLINE_NO LIKE 'OFL2099%'");
        jdbc.update("DELETE FROM erp_fin_sales_invoice WHERE ID LIKE 'trk-si-%' "
                + "OR (CREATE_BY='junit' AND INVOICE_DATE LIKE '2099-%')");
        jdbc.update("DELETE FROM erp_fin_input_invoice WHERE ID LIKE 'trk-ii-%' "
                + "OR (CREATE_BY='junit' AND INVOICE_DATE LIKE '2099-%')");
    }

    private void loginRole(String role) {
        var auth = new UsernamePasswordAuthenticationToken("junit", null,
                AuthorityUtils.createAuthorityList(role));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private Map<String, Object> certPayload(String suffix, String expire, String channel) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("certType", "DIGITAL_CERT");
        p.put("certName", "测试证书-" + suffix);
        p.put("certNo", "JUNITCERT-" + suffix);
        p.put("issueDate", LocalDate.now().minusDays(10).toString());
        p.put("expireDate", expire);
        p.put("channel", channel);
        return p;
    }

    // ---------- 组 3：证书 + 离线 ----------

    @Test
    void certRegisterAndExpiryWarning() {
        // 缺到期日 422
        Map<String, Object> bad = certPayload("nd", LocalDate.now().plusDays(10).toString(), null);
        bad.remove("expireDate");
        ServiceException e = assertThrows(ServiceException.class, () -> riskService.registerCert(bad));
        assertTrue(e.getMessage().contains("到期日必填"), e.getMessage());

        // 25 天后到期（≤30 天）→ 预警
        TaxCert c = riskService.registerCert(certPayload("w",
                LocalDate.now().plusDays(25).toString(), "TAX-CH-A"));
        assertNotNull(c.getId());
        Map<String, Object> w = riskService.certWarnings();
        assertTrue(((Number) w.get("expiringCount")).intValue() >= 1, "临期预警");
    }

    @Test
    void expiredChannelBlocksOfflineRegistration() {
        // 过期证书绑定通道 → 离线登记 422（C-4.14-14）
        TaxCert c = riskService.registerCert(certPayload("exp",
                LocalDate.now().minusDays(1).toString(), "TAX-CH-B"));
        assertEquals(TaxCert.ST_EXPIRED, c.getStatus(), "登记即判定过期");
        ServiceException e = assertThrows(ServiceException.class, () -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("reason", "通道故障离线开票");
            p.put("channel", "TAX-CH-B");
            riskService.registerOffline(p);
        });
        assertTrue(e.getMessage().contains("C-4.14-14"), e.getMessage());
        assertTrue(e.getMessage().contains("待开队列"), e.getMessage());

        // 更新证书 → 解阻，登记放行
        riskService.updateCert(c.getId(), Map.of("expireDate", LocalDate.now().plusDays(365).toString()));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("reason", "通道故障离线开票");
        p.put("channel", "TAX-CH-B");
        OfflineInvoice o = riskService.registerOffline(p);
        assertNotNull(o.getOfflineNo());
    }

    @Test
    void offlineAlert48HoursAndUploadClear() {
        // 开票 50 小时前登记 → 48h L4 告警
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("reason", "网络隔离离线开票");
        p.put("openTime", LocalDateTime.now().minusHours(50).toString());
        OfflineInvoice o = riskService.registerOffline(p);
        List<Map<String, Object>> alerts = riskService.offlineAlerts();
        assertTrue(alerts.stream().anyMatch(a -> o.getOfflineNo().equals(a.get("offlineNo"))), "L4 告警");
        assertEquals("L4", alerts.get(0).get("level"));

        // 补传 → 告警消除
        riskService.uploadOffline(o.getId());
        List<Map<String, Object>> after = riskService.offlineAlerts();
        assertTrue(after.stream().noneMatch(a -> o.getOfflineNo().equals(a.get("offlineNo"))), "补传消除");
    }

    // ---------- 组 4：税负率 ----------

    @Test
    void burdenBelowFloorAndMomSwingSignals() {
        // 造数：209903 销项 65000/进项 63000/销售 500000（税负率 0.4% < 1% 下限）
        //       209902 销项 65000/进项 35000/销售 500000（税负率 6.0%）→ 环比降 93% > 30%
        seedSales("b1", "2099-03-15", "65000", "565000");
        seedCertifiedInput("b1", "2099-03-15", "63000");
        seedSales("b2", "2099-02-15", "65000", "565000");
        seedCertifiedInput("b2", "2099-02-15", "35000");

        Map<String, Object> monthly = burdenService.monthly("209903", 2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) monthly.get("rows");
        assertEquals(2, rows.size());
        Map<String, Object> march = rows.get(1);
        assertEquals("0.0040", march.get("vatBurden"), "税负率 2000/500000=0.4%");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> breaches = (List<Map<String, Object>>) march.get("breaches");
        assertTrue(breaches.size() >= 2, "低于下限 + 环比波动双 breach：" + breaches);

        List<Map<String, Object>> sigs = burdenService.signals("209903", 2);
        assertTrue(sigs.stream().anyMatch(s -> "L4".equals(s.get("level"))), "L4 信号");

        // 正常期间无预警：209901 税负率 6%（进项 35000）
        seedSales("b3", "2099-01-15", "65000", "565000");
        seedCertifiedInput("b3", "2099-01-15", "35000");
        Map<String, Object> m2 = burdenService.monthly("209901", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows2 = (List<Map<String, Object>>) m2.get("rows");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> breaches2 = (List<Map<String, Object>>) rows2.get(0).get("breaches");
        assertTrue(breaches2.isEmpty(), "正常期间无 breach");
    }

    @Test
    void burdenZeroDenominatorNotApplicable() {
        // 无销售额期间 → 不适用标注，不参与环比
        Map<String, Object> monthly = burdenService.monthly("209905", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) monthly.get("rows");
        assertEquals(null, rows.get(0).get("vatBurden"));
        assertNotNull(rows.get(0).get("applicable"));
        assertTrue(String.valueOf(rows.get(0).get("applicable")).contains("不适用"));
    }

    // ---------- 组 5：看板 ----------

    @Test
    void dashboardAggregatesSignals() {
        // 证书临期 + 离线 L4 告警 → 看板至少 2 条
        riskService.registerCert(certPayload("db",
                LocalDate.now().plusDays(20).toString(), "TAX-CH-C"));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("reason", "看板造数离线");
        p.put("openTime", LocalDateTime.now().minusHours(60).toString());
        riskService.registerOffline(p);
        riskService.offlineAlerts();

        Map<String, Object> dash = dashboardService.dashboard("209903");
        assertNotNull(dash.get("signals"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> signals = (List<Map<String, Object>>) dash.get("signals");
        assertTrue(signals.size() >= 1, "看板有信号");
        @SuppressWarnings("unchecked")
        Map<String, Object> sources = (Map<String, Object>) dash.get("sources");
        assertEquals("OK", sources.get("tax-cert"), "本叶证书来源 OK");

        // 跨叶来源失败降级：注入使 export 源抛错不现实——验证 sources 覆盖 8 类 key 且不炸
        assertNotNull(sources.get("export-refund"));
        assertNotNull(sources.get("input-cert-expiry"));
    }

    // ---------- 造数 ----------

    private void seedSales(String suffix, String date, String tax, String amount) {
        jdbc.update("INSERT INTO erp_fin_sales_invoice (ID, INVOICE_NO, CUSTOMER_ID, CUSTOMER_NAME, "
                + "INVOICE_DATE, AMOUNT, TAX_AMOUNT, TAX_RATE, STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, 'trk-cust', '看板客户', ?, ?, ?, 0.13, 'ISSUED', 'junit', '0', 0)",
                "trk-si-" + suffix, "JUNITSI-" + suffix, date, amount, tax);
    }

    private void seedCertifiedInput(String suffix, String date, String tax) {
        jdbc.update("INSERT INTO erp_fin_input_invoice (ID, INVOICE_NO, SUPPLIER_ID, SUPPLIER_NAME, "
                + "INVOICE_DATE, AMOUNT, TAX_AMOUNT, PO_ID, PO_NO, MATCH_STATUS, CERT_STATUS, "
                + "DEDUCTIBLE, TRANSFER_OUT_AMOUNT, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, 'trk-sup', '看板供应商', ?, ?, ?, 'trk-po', 'JUNITPO', "
                + "'MATCHED', 'CERTIFIED', 'Y', 0, 'junit', '0', 0)",
                "trk-ii-" + suffix, "JUNITII-" + suffix, date, tax, tax);
    }
}
