package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.InputInvoiceDao;
import com.erp.dao.proc.PurchaseOrderDao;
import com.erp.dao.proc.PurchaseOrderLineDao;
import com.erp.entity.fin.InputInvoice;
import com.erp.entity.proc.PurchaseOrder;
import com.erp.service.SysParamService;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.fin.InputInvoiceService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 进项发票管理实现（M9-2-2，D1~D4）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InputInvoiceServiceImpl implements InputInvoiceService {

    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final InputInvoiceDao inputDao;
    private final PurchaseOrderDao poDao;
    private final PurchaseOrderLineDao poLineDao;
    private final GlVoucherService voucherService;
    private final SysParamService sysParamService;

    // ---------- 2.1 登记 + 三单匹配（D2） ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> register(Map<String, Object> payload) {
        String invoiceNo = str(payload.get("invoiceNo"));
        String supplierId = str(payload.get("supplierId"));
        String poId = str(payload.get("poId"));
        BigDecimal amount = dec(payload.get("amount"), null);
        BigDecimal taxAmount = dec(payload.get("taxAmount"), null);
        LocalDate invoiceDate = parseDate(str(payload.get("invoiceDate")));
        if (!hasText(invoiceNo) || !hasText(supplierId) || !hasText(poId)
                || amount == null || taxAmount == null || invoiceDate == null) {
            throw new ServiceException(422, "登记要素必填（票号/供应商/PO/金额/税额/开票日）");
        }
        // 票号唯一
        Long dup = inputDao.selectCount(new LambdaQueryWrapper<InputInvoice>()
                .eq(InputInvoice::getInvoiceNo, invoiceNo.trim())
                .eq(InputInvoice::getDelFlag, "0"));
        if (dup != null && dup > 0) {
            throw new ServiceException(422, "发票号已登记：" + invoiceNo);
        }
        // PO 存在 + 供应商一致性（L1）
        PurchaseOrder po = poDao.selectById(poId.trim());
        if (po == null) {
            throw new ServiceException(422, "关联 PO 不存在：" + poId);
        }
        if (!supplierId.trim().equals(po.getSupplierId())) {
            throw new ServiceException(422, "三单匹配失败：发票供应商（" + supplierId
                    + "）与 PO 供应商（" + po.getSupplierId() + "）不一致（L1 阻断）");
        }
        // D2（add-input-certification）：inspect=true → 登记即查验 PASS 直入匹配（兼容现行为）；
        // 缺省 → 落 PENDING_INSPECT（已接收未查验——不参与认证/凭证，匹配后移至查验通过时）
        boolean inspectNow = Boolean.TRUE.equals(payload.get("inspect"))
                || "true".equalsIgnoreCase(str(payload.get("inspect")));
        InputInvoice inv = new InputInvoice();
        inv.setInvoiceNo(invoiceNo.trim());
        inv.setSupplierId(supplierId.trim());
        inv.setSupplierName(hasText(str(payload.get("supplierName")))
                ? str(payload.get("supplierName")) : po.getSupplierName());
        inv.setInvoiceDate(invoiceDate);
        inv.setAmount(amount);
        inv.setTaxAmount(taxAmount);
        inv.setPoId(poId.trim());
        inv.setPoNo(po.getPoNo());
        if (inspectNow) {
            // 查验 PASS 留痕 + 三单匹配判定（原行为）
            inv.setInspectMethod(hasText(str(payload.get("inspectMethod")))
                    ? str(payload.get("inspectMethod")).trim().toUpperCase() : "MANUAL");
            inv.setInspectResult("PASS");
            inv.setInspectAt(LocalDateTime.now());
            applyMatch(inv, po, amount);
            inv.setCertStatus(InputInvoice.CERT_PENDING);
        } else {
            inv.setMatchStatus(InputInvoice.MATCH_PENDING); // 匹配后移至查验通过时（D3）
            inv.setCertStatus(InputInvoice.CERT_PENDING_INSPECT);
        }
        inv.setDeductible("Y");
        inv.setRemark(str(payload.get("remark")));
        inv.setCreateBy(SecurityUtils.getCurrentUserId());
        inv.setCreateDate(LocalDateTime.now());
        inv.setVerNo(0);
        inv.setDelFlag("0");
        inputDao.insert(inv);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", inv.getId());
        out.put("invoiceNo", inv.getInvoiceNo());
        out.put("certStatus", inv.getCertStatus());
        out.put("matchStatus", inv.getMatchStatus());
        out.put("amountDiffRate", inv.getAmountDiffRate());
        out.put("matchDetail", inv.getMatchDetail());
        return out;
    }

    /** 三单匹配判定（D2 原逻辑抽出——register(inspect=true) 与 inspect(PASS) 两处复用） */
    private void applyMatch(InputInvoice inv, PurchaseOrder po, BigDecimal amount) {
        BigDecimal certified = inputDao.sumCertifiedByPo(inv.getPoId());
        BigDecimal outstanding = po.getTotalAmt().subtract(certified == null ? BigDecimal.ZERO : certified);
        BigDecimal diffRate = BigDecimal.ZERO;
        boolean overTolerance = false;
        if (outstanding.signum() > 0) {
            diffRate = amount.subtract(outstanding).abs()
                    .divide(outstanding, 6, RoundingMode.HALF_UP);
            BigDecimal tolerance = sysParamService.getRate("TOLERANCE_DEFAULT", new BigDecimal("0.005"));
            overTolerance = diffRate.compareTo(tolerance) > 0;
        }
        inv.setMatchStatus(overTolerance ? InputInvoice.MATCH_DIFF : InputInvoice.MATCH_MATCHED);
        inv.setAmountDiffRate(diffRate);
        inv.setMatchDetail("PO 总额 " + po.getTotalAmt() + "，已认证累计 " + certified
                + "，未开票余额 " + outstanding + "，发票 " + amount
                + "，差异率 " + diffRate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%");
    }

    @Override
    public List<InputInvoice> list(String certStatus, String matchStatus, String supplierId, String period) {
        LambdaQueryWrapper<InputInvoice> q = new LambdaQueryWrapper<InputInvoice>()
                .eq(InputInvoice::getDelFlag, "0")
                .orderByDesc(InputInvoice::getCreateDate);
        if (hasText(certStatus)) {
            q.eq(InputInvoice::getCertStatus, certStatus.trim().toUpperCase());
        }
        if (hasText(matchStatus)) {
            q.eq(InputInvoice::getMatchStatus, matchStatus.trim().toUpperCase());
        }
        if (hasText(supplierId)) {
            q.eq(InputInvoice::getSupplierId, supplierId.trim());
        }
        if (hasText(period)) {
            q.eq(InputInvoice::getCertPeriod, period.trim());
        }
        return inputDao.selectList(q);
    }

    // ---------- 查验流程闸口（add-input-certification D3：FR-4.14-3-2） ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InputInvoice inspect(String id, String method, String result) {
        InputInvoice inv = require(id);
        // 仅 PENDING_INSPECT 可查验（已查验/已作废均 422）
        if (!InputInvoice.CERT_PENDING_INSPECT.equals(inv.getCertStatus())) {
            throw new ServiceException(422, "仅待查验（PENDING_INSPECT）发票可查验，当前状态 "
                    + inv.getCertStatus());
        }
        boolean fail = "FAIL".equalsIgnoreCase(str(result));
        inv.setInspectMethod(hasText(method) ? method.trim().toUpperCase() : "MANUAL");
        inv.setInspectResult(fail ? "FAIL" : "PASS");
        inv.setInspectAt(LocalDateTime.now());
        if (fail) {
            // FAIL → INVALID 终态（留档不删；阻断发生在下游 certify 前置校验——FR-4.14-3-2 L1）
            inv.setCertStatus(InputInvoice.CERT_INVALID);
            inv.setCertNote("发票查验不存在/已作废（FR-4.14-3-2 阻断入库）：留档备查，不得认证抵扣/生成凭证");
        } else {
            // PASS → 执行三单匹配判定（后移的匹配逻辑）并进 PENDING_CERT
            PurchaseOrder po = poDao.selectById(inv.getPoId());
            if (po == null) {
                throw new ServiceException(422, "关联 PO 不存在：" + inv.getPoId());
            }
            applyMatch(inv, po, inv.getAmount());
            inv.setCertStatus(InputInvoice.CERT_PENDING);
        }
        inv.setUpdateBy(SecurityUtils.getCurrentUserId());
        inv.setUpdateDate(LocalDateTime.now());
        inputDao.updateById(inv);
        return inv;
    }

    // ---------- 2.2 认证登记（D3 三卡控） ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InputInvoice certify(String id, String certDate) {
        InputInvoice inv = require(id);
        // 查验前置（add-input-certification D4：FR-4.14-3-2）
        if (InputInvoice.CERT_PENDING_INSPECT.equals(inv.getCertStatus())) {
            throw new ServiceException(422, "未完成真伪查验（PENDING_INSPECT），"
                    + "请先查验通过再认证（FR-4.14-3-2）");
        }
        if (InputInvoice.CERT_INVALID.equals(inv.getCertStatus())) {
            throw new ServiceException(422, "发票查验失败已作废（INVALID，FR-4.14-3-2 阻断入库），"
                    + "不得认证抵扣");
        }
        // C-4.14-10 重复认证 422
        if (!InputInvoice.CERT_PENDING.equals(inv.getCertStatus())) {
            throw new ServiceException(422, "该发票已认证（" + inv.getCertStatus()
                    + "），认证日 " + inv.getCertDate() + "，所属期 " + inv.getCertPeriod()
                    + "（C-4.14-10 重复认证阻断）");
        }
        // DIFF 阻断认证
        if (InputInvoice.MATCH_DIFF.equals(inv.getMatchStatus())) {
            throw new ServiceException(422, "三单匹配超容差（" + inv.getMatchDetail()
                    + "），需采购确认后方可认证");
        }
        LocalDate cert = parseDate(certDate);
        if (cert == null) {
            throw new ServiceException(422, "认证日期必填（yyyy-MM-dd）");
        }
        // C-4.14-07 超期自动不可抵扣
        int certifyDays = sysParamService.getInt("INPUT_TAX_CERTIFY_DAYS", 360);
        long age = java.time.temporal.ChronoUnit.DAYS.between(inv.getInvoiceDate(), cert);
        boolean overExpiry = age > certifyDays;
        inv.setCertDate(cert);
        inv.setCertPeriod(cert.format(PERIOD_FMT)); // C-4.14-11 锁定
        if (overExpiry) {
            inv.setCertStatus(InputInvoice.CERT_NOT_DEDUCTIBLE);
            inv.setDeductible("N");
            inv.setCertNote("开票日距认证日 " + age + " 天 > " + certifyDays
                    + " 天，超认证期限：进项税额不可抵扣、计入成本费用（C-4.14-07）");
        } else {
            inv.setCertStatus(InputInvoice.CERT_CERTIFIED);
        }
        inv.setUpdateBy(SecurityUtils.getCurrentUserId());
        inv.setUpdateDate(LocalDateTime.now());
        inputDao.updateById(inv);
        return inv;
    }

    // ---------- 2.3 批量进项凭证（D4） ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generateVouchers(String period) {
        if (!hasText(period) || !period.trim().matches("\\d{6}")) {
            throw new ServiceException(422, "期间格式须为 YYYYMM");
        }
        List<InputInvoice> pending = inputDao.selectPendingVoucherByPeriod(period.trim());
        int generated = 0;
        List<String> voucherNos = new ArrayList<>();
        for (InputInvoice inv : pending) {
            boolean deductible = "Y".equals(inv.getDeductible());
            String drAcct = deductible ? "222101" : "660299";
            String summary = "进项税额 " + inv.getInvoiceNo()
                    + (deductible ? "" : "（超期不可抵扣 C-4.14-07）");
            List<GlVoucherService.FinVoucherLineSpec> lines = List.of(
                    GlVoucherService.FinVoucherLineSpec.of(drAcct, "DR", inv.getTaxAmount(), summary),
                    GlVoucherService.FinVoucherLineSpec.of("2202", "CR", inv.getTaxAmount(),
                            "进项 " + inv.getSupplierName()));
            LocalDate vDate = lastDayOf(period.trim());
            var v = voucherService.create("TAXIN", vDate, summary,
                    "TAXIN", inv.getInvoiceNo(), inv.getSupplierId(), lines, true);
            inv.setVoucherId(v.getId());
            inv.setUpdateBy(SecurityUtils.getCurrentUserId());
            inv.setUpdateDate(LocalDateTime.now());
            inputDao.updateById(inv);
            voucherNos.add(v.getVoucherNo());
            generated++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period.trim());
        out.put("generated", generated);
        out.put("skipped", 0);
        out.put("voucherNos", voucherNos);
        return out;
    }

    @Override
    public List<InputInvoice> nearingExpiry() {
        int certifyDays = sysParamService.getInt("INPUT_TAX_CERTIFY_DAYS", 360);
        int warnDays = sysParamService.getInt("INPUT_TAX_CERTIFY_WARNING_DAYS", 30);
        return inputDao.selectNearingExpiry(certifyDays, warnDays);
    }

    // ---------- helpers ----------

    private InputInvoice require(String id) {
        InputInvoice inv = inputDao.selectById(id);
        if (inv == null || "1".equals(inv.getDelFlag())) {
            throw new ServiceException(422, "进项发票不存在：" + id);
        }
        return inv;
    }

    private LocalDate lastDayOf(String period) {
        int y = Integer.parseInt(period.substring(0, 4));
        int m = Integer.parseInt(period.substring(4, 6));
        return LocalDate.of(y, m, 1).withDayOfMonth(LocalDate.of(y, m, 1).lengthOfMonth());
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static BigDecimal dec(Object o, BigDecimal dflt) {
        if (o == null || (o instanceof String s && s.isBlank())) {
            return dflt;
        }
        return o instanceof BigDecimal bd ? bd : new BigDecimal(String.valueOf(o));
    }

    private static LocalDate parseDate(String s) {
        if (!hasText(s)) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (RuntimeException e) {
            throw new ServiceException(422, "日期格式须为 yyyy-MM-dd");
        }
    }
}
