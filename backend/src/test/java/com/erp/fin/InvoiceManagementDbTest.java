package com.erp.fin;

import com.erp.common.ServiceException;
import com.erp.dao.fin.DecodedRequestDao;
import com.erp.dao.fin.DigitalQuotaDao;
import com.erp.dao.fin.InputInvoiceDao;
import com.erp.dao.fin.InvoiceDeliveryDao;
import com.erp.entity.fin.DecodedRequest;
import com.erp.entity.fin.DigitalQuota;
import com.erp.entity.fin.InputInvoice;
import com.erp.entity.fin.InvoiceDelivery;
import com.erp.service.SysParamService;
import com.erp.service.fin.DigitalInvoiceService;
import com.erp.service.fin.InputInvoiceService;
import com.erp.service.fin.InvoiceDeliveryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 发票管理 DbTest（add-invoice-management：spec invoice-management 7 需求 18 场景）。
 * 共享库隔离：整域清理（同 TAXCALC 先例）+ junit 造数 + 期间 209903。
 */
@SpringBootTest
class InvoiceManagementDbTest {

    private static final String PERIOD = "209903";

    @Autowired
    private InputInvoiceService inputService;
    @Autowired
    private DigitalInvoiceService digitalService;
    @Autowired
    private InvoiceDeliveryService deliveryService;
    @Autowired
    private InputInvoiceDao inputDao;
    @Autowired
    private DecodedRequestDao requestDao;
    @Autowired
    private DigitalQuotaDao quotaDao;
    @Autowired
    private InvoiceDeliveryDao deliveryDao;
    @Autowired
    private SysParamService sysParamService;
    @Autowired
    private JdbcTemplate jdbc;

    private String poId;
    private String poSupplierId;

    @BeforeEach
    void setUp() {
        purge();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("junit", null, "ROLE_FINANCE"));
        // 造一张测试 PO（供应商 junit-sup，总额 50000）
        poId = "junit-po-inv";
        poSupplierId = "junit-sup-inv";
        jdbc.update("INSERT INTO erp_proc_po (ID, PO_NO, PO_TYPE, SOURCE, SUPPLIER_ID, SUPPLIER_NAME, STATUS, "
                + "TOTAL_AMT, CURRENCY, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, 'JUNITPO01', 'NORMAL', 'MANUAL', ?, '冒烟供应商', 'CONFIRMED', 50000, 'CNY', 'junit', '0', 0)",
                poId, poSupplierId);
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE t FROM erp_fin_voucher_line t JOIN erp_fin_voucher v ON v.ID = t.VOUCHER_ID "
                + "WHERE v.SOURCE_TYPE = 'TAXIN'");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE = 'TAXIN'");
        jdbc.update("DELETE FROM erp_fin_input_invoice");
        jdbc.update("DELETE FROM erp_decoded_request");
        jdbc.update("DELETE FROM erp_fin_digital_quota");
        jdbc.update("DELETE FROM erp_fin_invoice_delivery");
        jdbc.update("DELETE FROM erp_proc_po WHERE ID = 'junit-po-inv'");
        // 造数期间额度余额行回收（防跨测试污染）
        jdbc.update("DELETE FROM erp_fin_balance WHERE PERIOD = '209903' "
                + "AND ACCOUNT_CODE IN ('222101','2202','660299')");
    }

    // ================================================================
    // 2.4 三单匹配 + 认证三卡控 + 批量凭证
    // ================================================================

    @Test
    void registerMatchedWithinTolerance() {
        // PO 总额 50000，发票 49900（差 0.2% ≤ 0.5%）→ MATCHED
        Map<String, Object> out = inputService.register(payload("JUNIT-INV-001", "49900", "2994"));
        assertEquals("MATCHED", out.get("matchStatus"));
    }

    @Test
    void registerDiffOverTolerance() {
        // 发票 48000 vs PO 50000（差 4% > 0.5%）→ DIFF
        Map<String, Object> out = inputService.register(payload("JUNIT-INV-002", "48000", "2880"));
        assertEquals("DIFF", out.get("matchStatus"));
        // DIFF 阻断认证
        String id = (String) out.get("id");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> inputService.certify(id, "2099-03-10"));
        assertTrue(ex.getMessage().contains("采购确认"), ex.getMessage());
    }

    @Test
    void registerSupplierMismatch422() {
        Map<String, Object> p = payload("JUNIT-INV-003", "50000", "3000");
        p.put("supplierId", "junit-other-sup");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> inputService.register(p));
        assertTrue(ex.getMessage().contains("不一致"), ex.getMessage());
    }

    @Test
    void certifyTripleControls() {
        // 正常认证 → CERTIFIED + 所属期锁定
        Map<String, Object> out = inputService.register(payload("JUNIT-INV-010", "50000", "3000"));
        String id = (String) out.get("id");
        InputInvoice cert = inputService.certify(id, "2099-03-10");
        assertEquals("CERTIFIED", cert.getCertStatus());
        assertEquals(PERIOD, cert.getCertPeriod(), "C-4.14-11 所属期=认证日锁定");
        // 重复认证 422 回显原日期（C-4.14-10）
        ServiceException ex = assertThrows(ServiceException.class,
                () -> inputService.certify(id, "2099-03-15"));
        assertTrue(ex.getMessage().contains("2099-03-10"), ex.getMessage());
        assertTrue(ex.getMessage().contains("C-4.14-10"), ex.getMessage());
    }

    @Test
    void certifyOverExpiryNotDeductible() {
        // 开票日 2097-01-01，认证日 2099-03-10（> 360 天）→ NOT_DEDUCTIBLE（C-4.14-07）
        Map<String, Object> p = payload("JUNIT-INV-011", "50000", "3000");
        p.put("invoiceDate", "2097-01-01");
        Map<String, Object> out = inputService.register(p);
        InputInvoice cert = inputService.certify((String) out.get("id"), "2099-03-10");
        assertEquals("NOT_DEDUCTIBLE", cert.getCertStatus());
        assertEquals("N", cert.getDeductible());
        assertTrue(cert.getCertNote().contains("超认证期限"), cert.getCertNote());
    }

    @Test
    void generateVouchersIdempotent() {
        // 2 可抵扣 + 1 不可抵扣 + 1 未认证
        seedCertified("JUNIT-INV-020", "Y", "1000");
        seedCertified("JUNIT-INV-021", "Y", "2000");
        seedCertified("JUNIT-INV-022", "N", "300");
        seedPending("JUNIT-INV-023");
        Map<String, Object> out = inputService.generateVouchers(PERIOD);
        assertEquals(3, out.get("generated"), "未认证不参与生成");
        // 幂等：二次触发 0 生成
        Map<String, Object> again = inputService.generateVouchers(PERIOD);
        assertEquals(0, again.get("generated"), "幂等：已生成行跳过");
        // 不可抵扣行凭证借 660299 不进 222101
        List<String> drAccts = jdbc.queryForList(
                "SELECT l.ACCOUNT_CODE FROM erp_fin_voucher_line l "
                        + "JOIN erp_fin_voucher v ON v.ID = l.VOUCHER_ID "
                        + "WHERE v.SOURCE_TYPE = 'TAXIN' AND v.SOURCE_DOC_NO = 'JUNIT-INV-022' "
                        + "AND l.DIRECTION = 'DR'", String.class);
        assertEquals(List.of("660299"), drAccts);
    }

    // ================================================================
    // 3.4 数电额度 + 队列 + 补开拆分 + 勾稽
    // ================================================================

    @Test
    void quotaBlockAndIssueDeduct() {
        digitalService.saveQuota(quotaPayload("30000"));
        // 额度不足 422（BR-4.14-36）
        Map<String, Object> p = reqPayload("35000", "SO-Q1", "SHIP-Q1");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> digitalService.submitRequest(p));
        assertTrue(ex.getMessage().contains("额度不足"), ex.getMessage());
        // 正常提交 → 补开 → 额度扣减
        DecodedRequest r = digitalService.submitRequest(reqPayload("20000", "SO-Q2", "SHIP-Q2"));
        assertEquals("PENDING", r.getStatus());
        Map<String, Object> issued = digitalService.issue(r.getId());
        assertEquals("ISSUED", issued.get("status"));
        assertEquals(1, issued.get("splitCount"), "20000 ≤ 限额不拆分");
        assertEquals(0, new BigDecimal("10000").compareTo(
                (BigDecimal) issued.get("quotaAvailable")), "30000 − 20000 = 10000");
        // 重复补开 422（BR-4.14-37）
        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> digitalService.issue(r.getId()));
        assertTrue(ex2.getMessage().contains("幂等键"), ex2.getMessage());
        // 同幂等键再提交 422
        ServiceException ex3 = assertThrows(ServiceException.class,
                () -> digitalService.submitRequest(reqPayload("5000", "SO-Q2", "SHIP-Q2")));
        assertTrue(ex3.getMessage().contains("BR-4.14-37"), ex3.getMessage());
    }

    @Test
    void issueSplitOverLimit() {
        // INVOICE_MAX_AMOUNT=100000（种子），请求 250000 → 拆 3 张
        digitalService.saveQuota(quotaPayload("500000"));
        DecodedRequest r = digitalService.submitRequest(reqPayload("250000", "SO-S1", "SHIP-S1"));
        Map<String, Object> issued = digitalService.issue(r.getId());
        assertEquals(3, issued.get("splitCount"), "250000/100000 → 3 张（BR-4.14-15）");
        String nos = (String) issued.get("invoiceNos");
        assertEquals(3, nos.split(",").length);
        // 自动建 3 条交付 PENDING 行（D6 联动）
        @SuppressWarnings("unchecked")
        List<String> deliveryIds = (List<String>) issued.get("deliveryIds");
        assertEquals(3, deliveryIds.size());
    }

    @Test
    void reconcileAllGreen() {
        digitalService.saveQuota(quotaPayload("100000"));
        DecodedRequest r = digitalService.submitRequest(reqPayload("50000", "SO-R1", "SHIP-R1"));
        digitalService.issue(r.getId());
        Map<String, Object> out = digitalService.reconcile(PERIOD);
        assertEquals(Boolean.TRUE, out.get("allGreen"), "无漏开/重开 → 全绿");
    }

    // ================================================================
    // 4.3 交付 + 限额提示
    // ================================================================

    @Test
    void deliveryLifecycleAndOverdue() {
        // 数电补开自动建行 → 标记已发送
        digitalService.saveQuota(quotaPayload("100000"));
        DecodedRequest r = digitalService.submitRequest(reqPayload("30000", "SO-D1", "SHIP-D1"));
        Map<String, Object> issued = digitalService.issue(r.getId());
        @SuppressWarnings("unchecked")
        List<String> deliveryIds = (List<String>) issued.get("deliveryIds");
        InvoiceDelivery d = deliveryService.markSent(deliveryIds.get(0));
        assertEquals("SENT", d.getStatus());
        assertNotNull(d.getSentAt());
        // 重发超限 422（DELIVERY_MAX_RETRY=2）
        deliveryService.resend(deliveryIds.get(0));
        deliveryService.resend(deliveryIds.get(0));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> deliveryService.resend(deliveryIds.get(0)));
        assertTrue(ex.getMessage().contains("上限"), ex.getMessage());
    }

    @Test
    void deliveryOverdueYellowFlag() {
        // 造 10 天前的 PENDING 行（> 7 天阈值）→ overdue=true
        jdbc.update("INSERT INTO erp_fin_invoice_delivery (ID, INVOICE_NO, SOURCE, STATUS, RETRY_COUNT, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES ('junit-deliv-od', 'DSI-OD-001', 'DIGITAL', 'PENDING', 0, 'junit', "
                + "DATE_SUB(NOW(), INTERVAL 10 DAY), '0', 0)");
        List<Map<String, Object>> rows = deliveryService.list(null, "DIGITAL");
        Map<String, Object> row = rows.stream()
                .filter(r -> "junit-deliv-od".equals(r.get("id"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, row.get("overdue"), "10 天 > 7 天阈值 → 超期黄标");
    }

    // ================================================================
    // add-input-certification 2.1 查验闸口三场景（FR-4.14-3-2）
    // ================================================================

    @Test
    void pendingInspectCannotCertify() {
        // 不带 inspect → PENDING_INSPECT → 认证 422「未完成真伪查验」
        Map<String, Object> out = inputService.register(
                payloadPendingInspect("JUNIT-INV-030", "50000", "3000"));
        assertEquals("PENDING_INSPECT", out.get("certStatus"));
        assertEquals("PENDING", out.get("matchStatus"), "匹配后移至查验通过时（D3）");
        String id = (String) out.get("id");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> inputService.certify(id, "2099-03-10"));
        assertTrue(ex.getMessage().contains("未完成真伪查验"), ex.getMessage());
        assertTrue(ex.getMessage().contains("FR-4.14-3-2"), ex.getMessage());
    }

    @Test
    void inspectFailInvalidBlocked() {
        // FAIL → INVALID 终态 → 认证 422 含 FR-4.14-3-2 + 批量凭证跳过
        Map<String, Object> out = inputService.register(
                payloadPendingInspect("JUNIT-INV-031", "50000", "3000"));
        String id = (String) out.get("id");
        InputInvoice inv = inputService.inspect(id, "MANUAL", "FAIL");
        assertEquals("INVALID", inv.getCertStatus(), "FAIL → INVALID 终态（留档不删）");
        assertEquals("FAIL", inv.getInspectResult());
        ServiceException ex = assertThrows(ServiceException.class,
                () -> inputService.certify(id, "2099-03-10"));
        assertTrue(ex.getMessage().contains("阻断入库"), ex.getMessage());
        // 批量凭证扫描不含 INVALID 行（D1 显式 IN 收窄——造 CERT_PERIOD 后验证）
        jdbc.update("UPDATE erp_fin_input_invoice SET CERT_PERIOD = '209903' WHERE ID = ?", id);
        Map<String, Object> gen = inputService.generateVouchers("209903");
        assertEquals(0, gen.get("generated"), "INVALID 行不参与批量凭证");
    }

    @Test
    void inspectPassProceedsToMatch() {
        // PASS → 执行三单匹配（后移逻辑）→ MATCHED → 可认证
        Map<String, Object> out = inputService.register(
                payloadPendingInspect("JUNIT-INV-032", "49900", "2994"));
        String id = (String) out.get("id");
        InputInvoice inv = inputService.inspect(id, "MANUAL", "PASS");
        assertEquals("PENDING_CERT", inv.getCertStatus());
        assertEquals("MATCHED", inv.getMatchStatus(), "查验 PASS 时执行三单匹配（49900/50000=0.2% 内）");
        InputInvoice cert = inputService.certify(id, "2099-03-10");
        assertEquals("CERTIFIED", cert.getCertStatus(), "查验通过后认证放行");
        // 已查验再查验 422 / INVALID 再查验 422
        ServiceException ex = assertThrows(ServiceException.class,
                () -> inputService.inspect(id, "MANUAL", "PASS"));
        assertTrue(ex.getMessage().contains("仅待查验"), ex.getMessage());
    }

    @Test
    void limitCheckSuggestsSplit() {
        // 250000 / 100000 → 建议 3 张（L4 提示，与控制器同口径——此处直接验参数）
        BigDecimal limit = sysParamService.getAmount("INVOICE_MAX_AMOUNT", new BigDecimal("100000"));
        assertEquals(0, new BigDecimal("100000").compareTo(limit));
        BigDecimal amount = new BigDecimal("250000");
        int suggested = amount.divide(limit, 0, java.math.RoundingMode.CEILING).intValue();
        assertEquals(3, suggested, "BR-4.14-15 建议拆分数");
    }

    // ---------- helpers ----------

    private Map<String, Object> payload(String invoiceNo, String amount, String tax) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("invoiceNo", invoiceNo);
        p.put("supplierId", poSupplierId);
        p.put("supplierName", "冒烟供应商");
        p.put("poId", poId);
        p.put("invoiceDate", "2099-03-01");
        p.put("amount", amount);
        p.put("taxAmount", tax);
        // add-input-certification D6 兼容口径：既有 12 场景按「登记即查验直入匹配」原行为写就
        p.put("inspect", true);
        return p;
    }

    /** 不带 inspect 参数（add-input-certification D2：落 PENDING_INSPECT 待查验） */
    private Map<String, Object> payloadPendingInspect(String invoiceNo, String amount, String tax) {
        Map<String, Object> p = payload(invoiceNo, amount, tax);
        p.remove("inspect");
        return p;
    }

    private void seedCertified(String invoiceNo, String deductible, String tax) {
        jdbc.update("INSERT INTO erp_fin_input_invoice (ID, INVOICE_NO, SUPPLIER_ID, SUPPLIER_NAME, "
                + "INVOICE_DATE, AMOUNT, TAX_AMOUNT, PO_ID, PO_NO, MATCH_STATUS, CERT_STATUS, "
                + "CERT_DATE, CERT_PERIOD, DEDUCTIBLE, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, ?, '冒烟供应商', '2099-03-01', ?, ?, ?, 'JUNITPO01', 'MATCHED', "
                + "'CERTIFIED', '2099-03-05', ?, ?, 'junit', '0', 0)",
                "junit-" + invoiceNo, invoiceNo, poSupplierId, new BigDecimal(tax).add(new BigDecimal("100")),
                tax, poId, PERIOD, deductible);
    }

    private void seedPending(String invoiceNo) {
        jdbc.update("INSERT INTO erp_fin_input_invoice (ID, INVOICE_NO, SUPPLIER_ID, SUPPLIER_NAME, "
                + "INVOICE_DATE, AMOUNT, TAX_AMOUNT, PO_ID, PO_NO, MATCH_STATUS, CERT_STATUS, "
                + "DEDUCTIBLE, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, ?, '冒烟供应商', '2099-03-01', 500, 30, ?, 'JUNITPO01', 'MATCHED', "
                + "'PENDING_CERT', 'Y', 'junit', '0', 0)",
                "junit-" + invoiceNo, invoiceNo, poSupplierId, poId);
    }

    private Map<String, Object> quotaPayload(String total) {
        // submitRequest/issue 按真实当期查额度（服务端口径 LocalDate.now()）
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("period", java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")));
        p.put("totalLimit", total);
        return p;
    }

    private Map<String, Object> reqPayload(String amount, String soNo, String shipNo) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("clientName", "冒烟客户");
        p.put("amount", amount);
        p.put("soNo", soNo);
        p.put("shipNo", shipNo);
        p.put("lineNo", "10");
        return p;
    }
}
