package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.fin.ArInvoiceDao;
import com.erp.dao.fin.RedFlushApplyDao;
import com.erp.dao.fin.RedInvoiceDao;
import com.erp.dao.fin.SalesInvoiceDao;
import com.erp.dao.sd.InvoiceApplyDao;
import com.erp.entity.fin.ArInvoice;
import com.erp.entity.fin.RedFlushApply;
import com.erp.entity.fin.RedInvoice;
import com.erp.entity.fin.SalesInvoice;
import com.erp.entity.sd.InvoiceApply;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.SysParamService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.fin.FinPeriodService;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.fin.RedFlushService;
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
import java.util.UUID;

/**
 * 红字冲销实现（M9-4-1，spec red-invoice-void，design D1/D2/D3/D6）。
 *
 * 卡控内生：
 *  - C-4.14-02：红冲金额 ≤ 原票金额 − 已累计红冲（剩余可红冲），超出 422 提示余额；
 *  - C-4.14-12/BR-4.14-28：原票 VOID / 已全额红冲 → 422「无有效蓝字发票」/「原票已全额红冲」；
 *  - BR-4.14-25：信息表填开方按购方认证抵扣状态判定（系内代理：该客户存在 CONFIRMED 发票）；
 *  - 编号门禁：信息表编号未录入不得开红票；
 *  - 并发防超冲：开具前 selectForUpdate 锁原票 + 剩余可红冲复核。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedFlushServiceImpl implements RedFlushService {

    public static final String BIZ_TYPE = "RedFlush";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final RedFlushApplyDao applyDao;
    private final SalesInvoiceDao invoiceDao;
    private final RedInvoiceDao redDao;
    private final ArInvoiceDao arDao;
    private final InvoiceApplyDao invoiceApplyDao;
    private final ApprovalEngine approvalEngine;
    private final GlVoucherService voucherService;
    private final SysParamService sysParamService;
    private final FinPeriodService finPeriodService;

    // ================================================================
    // 1. 申请创建（FR-4.14-4-1 + C-4.14-02 + C-4.14-12）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(Map<String, Object> payload) {
        String originId = str(payload.get("originInvoiceId"));
        BigDecimal amount = dec(payload.get("amount"));
        String reasonType = str(payload.get("reasonType"));
        String reason = str(payload.get("reason"));
        if (!hasText(originId) || amount == null || !hasText(reasonType) || !hasText(reason)) {
            throw new ServiceException(422, "红冲申请要素必填（原蓝票/金额/原因类型/原因）");
        }
        if (amount.signum() <= 0) {
            throw new ServiceException(422, "红冲金额必须大于 0");
        }
        SalesInvoice origin = invoiceDao.selectById(originId.trim());
        assertRedFlushable(origin);

        // C-4.14-02：剩余可红冲 = 原票金额 − 已累计红冲
        BigDecimal remain = nvl(origin.getAmount()).subtract(nvl(origin.getRedAmount()));
        if (amount.compareTo(remain) > 0) {
            throw new ServiceException(422, "红冲金额超出剩余可红冲金额 " + strip(remain)
                    + "（C-4.14-02）");
        }

        RedFlushApply a = new RedFlushApply();
        a.setId(uuid());
        a.setApplyNo(nextNo("FSAP", applyDao::selectNosByPrefix));
        a.setOriginInvoiceId(origin.getId());
        a.setOriginInvoiceNo(origin.getInvoiceNo());
        a.setCustomerId(origin.getCustomerId());
        a.setCustomerCode(origin.getCustomerCode());
        a.setCustomerName(origin.getCustomerName());
        a.setAmount(amount);
        a.setTaxRate(origin.getTaxRate());
        a.setTaxAmount(amount.subtract(netOf(amount, origin.getTaxRate())));
        a.setReasonType(reasonType.trim());
        a.setReason(reason.trim());
        a.setStatus(RedFlushApply.ST_DRAFT);
        // 跨期判定（BR-4.14-27）：原票所属期 ≠ 当前税期 → 打标（审批意见须含更正申报评估）
        String currentPeriod = currentPeriod();
        String originPeriod = origin.getInvoiceDate() == null ? null
                : origin.getInvoiceDate().format(PERIOD_FMT);
        boolean crossPeriod = originPeriod != null && !originPeriod.equals(currentPeriod);
        a.setCrossPeriodFlag(crossPeriod ? "1" : "0");
        a.setFreqFlag("0");
        a.setCreateBy(currentUser());
        applyDao.insert(a);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", a.getId());
        out.put("applyNo", a.getApplyNo());
        out.put("status", a.getStatus());
        out.put("remainBefore", remain);
        out.put("crossPeriod", crossPeriod);
        out.put("originInvoiceNo", origin.getInvoiceNo());
        return out;
    }

    // ================================================================
    // 2. 提交审批（FR-4.14-4-2：金额分级 + 频繁红冲升级）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submit(String applyId) {
        RedFlushApply a = require(applyId);
        if (!RedFlushApply.ST_DRAFT.equals(a.getStatus())) {
            throw new ServiceException(422, "仅草稿态可提交审批（当前：" + a.getStatus() + "）");
        }
        BigDecimal threshold = sysParamService.getAmount("RED_FLUSH_APPROVAL_THRESHOLD",
                new BigDecimal("100000"));
        boolean overThreshold = nvl(a.getAmount()).compareTo(threshold) >= 0;

        // 频繁红冲升级（FR-4.14-4-2）：同客户窗口内实质申请数 ≥ 次数阈值 → 追加财务经理
        int freqDays = sysParamService.getInt("RED_FLUSH_FREQ_DAYS", 30);
        int freqCount = sysParamService.getInt("RED_FLUSH_FREQ_COUNT", 3);
        int windowCount = applyDao.countFreqByCustomerSince(a.getCustomerId(),
                LocalDateTime.now().minusDays(freqDays));
        boolean freqUpgrade = windowCount >= freqCount;
        a.setFreqFlag(freqUpgrade ? "1" : "0");

        // 分级：金额 < 阈值 → 税务专员主管单级；≥ 阈值 → 主管 → 财务经理串行（升级语义）
        List<List<ApprovalNodeSpec>> chain = new ArrayList<>();
        chain.add(List.of(ApprovalNodeSpec.sign("ROLE_FINANCE_MGR", "税务专员主管审批")));
        if (overThreshold) {
            chain.add(List.of(ApprovalNodeSpec.sign("ROLE_FIN_MGR", "财务经理审批（超阈值）")));
        }
        if (freqUpgrade && !overThreshold) {
            chain.add(List.of(ApprovalNodeSpec.sign("ROLE_FIN_MGR",
                    "财务经理审批（频繁红冲升级：窗口 " + freqDays + " 天内 " + windowCount + " 单）")));
        }
        String title = "红冲申请 " + a.getApplyNo() + " 金额 " + strip(a.getAmount())
                + "：" + nvl(a.getCustomerName())
                + ("1".equals(a.getCrossPeriodFlag()) ? "【跨期红冲，意见须含更正申报评估】" : "");
        ApprovalInstance inst = approvalEngine.submit(BIZ_TYPE, a.getId(), title,
                "ROLE_FIN_MGR", chain);
        a.setApprId(inst.getId());
        applyDao.updateById(a);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", a.getId());
        out.put("applyNo", a.getApplyNo());
        out.put("apprId", inst.getId());
        out.put("apprNo", inst.getApprNo());
        out.put("threshold", threshold);
        out.put("overThreshold", overThreshold);
        out.put("freqUpgrade", freqUpgrade);
        out.put("windowCount", windowCount);
        out.put("chainSize", chain.size());
        return out;
    }

    // ================================================================
    // 3. 信息表编号登记 + 开红票（FR-4.14-4-3/4/5/6 + BR-4.14-25/26）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RedFlushApply recordInfoSheetNo(String applyId, String infoSheetNo) {
        RedFlushApply a = require(applyId);
        if (!RedFlushApply.ST_INFO_ISSUED.equals(a.getStatus())) {
            throw new ServiceException(422, "仅信息单已生成态可登记信息表编号（当前："
                    + a.getStatus() + "）");
        }
        if (!hasText(infoSheetNo)) {
            throw new ServiceException(422, "红字信息表编号必填");
        }
        a.setInfoSheetNo(infoSheetNo.trim());
        applyDao.updateById(a);
        log.info("red flush {} info sheet no recorded: {}", a.getApplyNo(), infoSheetNo);
        return a;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RedInvoice issue(String applyId) {
        RedFlushApply a = require(applyId);
        if (!RedFlushApply.ST_INFO_ISSUED.equals(a.getStatus())) {
            throw new ServiceException(422, "仅信息单已生成态可开红票（当前：" + a.getStatus() + "）");
        }
        // BR-4.14-25 编号门禁：信息表编号未录入不得开红票
        if (!hasText(a.getInfoSheetNo())) {
            throw new ServiceException(422, "红字信息表编号未录入，不得开具红字发票（BR-4.14-25）");
        }
        RedInvoice red = issueRedInvoice(a);
        a.setStatus(RedFlushApply.ST_ISSUED);
        a.setRedInvoiceId(red.getId());
        a.setRedNo(red.getRedNo());
        applyDao.updateById(a);
        log.info("red flush {} issued red invoice {} apply={}", a.getApplyNo(),
                red.getRedNo(), a.getId());
        return red;
    }

    /**
     * 开红票核心（原 redInvoice() 逻辑抽取，design D3）：锁原票 → 复核余额 → 红票落库
     * → 原票累计红冲（全额置 RED_FULL）→ AR 红冲回匹配池 → 关联开票申请置红 → 红冲凭证 DRAFT。
     */
    private RedInvoice issueRedInvoice(RedFlushApply a) {
        // 悲观锁原票 + 剩余可红冲复核（并发防超冲，design D6）
        SalesInvoice origin = invoiceDao.selectForUpdate(a.getOriginInvoiceId());
        assertRedFlushable(origin);
        BigDecimal remain = nvl(origin.getAmount()).subtract(nvl(origin.getRedAmount()));
        if (a.getAmount().compareTo(remain) > 0) {
            throw new ServiceException(422, "红冲金额超出剩余可红冲金额 " + strip(remain)
                    + "（原票状态可能已变化，C-4.14-02）");
        }
        BigDecimal rate = nvl(origin.getTaxRate());

        RedInvoice red = new RedInvoice();
        red.setId(uuid());
        red.setRedNo(nextNo("FPZ", redDao::selectNosByPrefix));
        red.setOriginInvoiceId(origin.getId());
        red.setOriginInvoiceNo(origin.getInvoiceNo());
        red.setArId(origin.getArId());
        red.setArNo(origin.getArNo());
        red.setCustomerId(origin.getCustomerId());
        red.setCustomerCode(origin.getCustomerCode());
        red.setCustomerName(origin.getCustomerName());
        red.setRedDate(LocalDate.now());
        red.setAmount(a.getAmount());
        red.setNetAmount(netOf(a.getAmount(), rate));
        red.setTaxRate(rate);
        red.setTaxAmount(a.getAmount().subtract(netOf(a.getAmount(), rate)));
        red.setReason(a.getReason());
        red.setStatus(RedInvoice.ST_ISSUED);
        red.setIssueBy(currentUser());
        red.setIssueAt(LocalDateTime.now());
        red.setInfoSheetNo(a.getInfoSheetNo());
        red.setInfoSheetFiller(a.getInfoSheetFiller());
        red.setFlushApplyNo(a.getApplyNo());
        red.setCreateBy(currentUser());
        redDao.insert(red);

        // 原票累计红冲 + 全额置态（BR-4.14-26 同步余额；C-4.14-12 全额后不可再冲）
        origin.setRedAmount(nvl(origin.getRedAmount()).add(a.getAmount()));
        BigDecimal newRemain = nvl(origin.getAmount()).subtract(origin.getRedAmount());
        if (newRemain.signum() <= 0) {
            origin.setStatus(SalesInvoice.ST_RED_FULL);
        }
        invoiceDao.updateById(origin);

        // 原应收红冲：余额 = AMOUNT − RED − PAID 回核销匹配池（D14 语义保留）
        ArInvoice ar = arDao.selectById(origin.getArId());
        if (ar != null) {
            ar.setRedAmount(nvl(ar.getRedAmount()).add(a.getAmount()));
            BigDecimal balance = nvl(ar.getAmount()).subtract(nvl(ar.getRedAmount()))
                    .subtract(nvl(ar.getPaidAmount()));
            ar.setStatus(balance.signum() <= 0 ? ArInvoice.ST_PAID
                    : (nvl(ar.getPaidAmount()).signum() > 0 ? ArInvoice.ST_PARTIAL
                    : ArInvoice.ST_UNPAID));
            arDao.updateById(ar);
        }
        // 关联开票申请置红字终态
        List<InvoiceApply> applies = invoiceApplyDao.selectList(new LambdaQueryWrapper<InvoiceApply>()
                .eq(InvoiceApply::getInvoiceId, origin.getId()));
        for (InvoiceApply ap : applies) {
            ap.setStatus(InvoiceApply.ST_RED);
            invoiceApplyDao.updateById(ap);
        }

        // 红冲凭证（FR-4.14-4-6，DRAFT 制单≠审核 C-4.6-07）：
        // 借 6001 主营业务收入（净额）+ 借 222102 销项税 / 贷 1122 应收账款（价税合计）
        var voucherLines = new ArrayList<GlVoucherService.FinVoucherLineSpec>();
        voucherLines.add(GlVoucherService.FinVoucherLineSpec.of("6001", "DR",
                red.getNetAmount(), "红冲-" + red.getRedNo()));
        voucherLines.add(GlVoucherService.FinVoucherLineSpec.of("222102", "DR",
                red.getTaxAmount(), "红冲-" + red.getRedNo()));
        voucherLines.add(GlVoucherService.FinVoucherLineSpec.of("1122", "CR",
                red.getAmount(), "红冲-" + red.getRedNo()));
        var v = voucherService.create("RFL", LocalDate.now(),
                "红冲凭证：" + red.getRedNo() + "（原票 " + origin.getInvoiceNo() + "）",
                "REDFLUSH", a.getApplyNo(), null, voucherLines, true);
        a.setVoucherId(v.getId());
        return red;
    }

    // ================================================================
    // 3b. 红冲后重开（BR-4.14-29：原开票申请复活 + 强制关联红冲单号）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reopen(String applyId, String reopenNote) {
        RedFlushApply a = require(applyId);
        if (!RedFlushApply.ST_ISSUED.equals(a.getStatus())) {
            throw new ServiceException(422, "仅已开票的红冲申请可发起重开（当前：" + a.getStatus() + "）");
        }
        if (!hasText(reopenNote)) {
            throw new ServiceException(422, "重开原因必填（BR-4.14-29）");
        }
        // 原蓝票的开票申请（红字终态）复活为 DRAFT，强制关联红冲单号（不可清空）
        List<InvoiceApply> applies = invoiceApplyDao.selectList(new LambdaQueryWrapper<InvoiceApply>()
                .eq(InvoiceApply::getInvoiceId, a.getOriginInvoiceId())
                .eq(InvoiceApply::getDelFlag, "0"));
        if (applies.isEmpty()) {
            throw new ServiceException(422, "原蓝票无关联开票申请，无法自动重开，请手工发起开票并关联红冲单 "
                    + a.getApplyNo());
        }
        InvoiceApply ap = applies.get(0);
        ap.setStatus(InvoiceApply.ST_DRAFT);
        ap.setRedFlushApplyNo(a.getApplyNo());
        ap.setReopenNote(reopenNote.trim());
        // 清空原开票结果锚（票号/应收回写在 issue 时重建）——null 字段须 UpdateWrapper 显式置空
        invoiceApplyDao.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InvoiceApply>()
                .eq(InvoiceApply::getId, ap.getId())
                .set(InvoiceApply::getStatus, InvoiceApply.ST_DRAFT)
                .set(InvoiceApply::getRedFlushApplyNo, a.getApplyNo())
                .set(InvoiceApply::getReopenNote, reopenNote.trim())
                .set(InvoiceApply::getInvoiceId, null)
                .set(InvoiceApply::getInvoiceNo, null)
                .set(InvoiceApply::getIssueBy, null)
                .set(InvoiceApply::getIssueAt, null));
        a.setReopenApplyNo(ap.getApplyNo());
        applyDao.updateById(a);
        log.info("red flush {} reopen: apply {} reset to DRAFT", a.getApplyNo(), ap.getApplyNo());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applyId", ap.getId());
        out.put("applyNo", ap.getApplyNo());
        out.put("redFlushApplyNo", a.getApplyNo());
        out.put("reopenNote", reopenNote);
        out.put("amount", ap.getApplyAmount());
        out.put("customerId", ap.getCustomerId());
        return out;
    }

    // ================================================================
    // 4. 查询
    // ================================================================

    @Override
    public Page<RedFlushApply> page(long current, long size, String status,
                                    String customerId, String keyword) {
        LambdaQueryWrapper<RedFlushApply> qw = new LambdaQueryWrapper<RedFlushApply>()
                .eq(RedFlushApply::getDelFlag, "0")
                .eq(hasText(status), RedFlushApply::getStatus, status)
                .eq(hasText(customerId), RedFlushApply::getCustomerId, customerId)
                .and(hasText(keyword), w -> w.like(RedFlushApply::getApplyNo, keyword)
                        .or().like(RedFlushApply::getOriginInvoiceNo, keyword)
                        .or().like(RedFlushApply::getCustomerName, keyword))
                .orderByDesc(RedFlushApply::getCreateDate);
        return applyDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> detail(String applyId) {
        RedFlushApply a = require(applyId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apply", a);
        if (hasText(a.getRedInvoiceId())) {
            out.put("redInvoice", redDao.selectById(a.getRedInvoiceId()));
        }
        if (hasText(a.getApprId())) {
            out.put("approval", approvalEngine.logs(BIZ_TYPE, a.getId()));
        }
        return out;
    }

    // ================================================================
    // 5. 旧接口兼容（design D3：签名保留，内部重定向申请单）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RedInvoice redirectLegacy(String originInvoiceId, String returnId, String returnNo,
                                     BigDecimal amount, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("originInvoiceId", originInvoiceId);
        payload.put("amount", amount);
        payload.put("reasonType", RedFlushApply.REASON_RETURN);
        payload.put("reason", hasText(reason) ? reason : "退货红冲");
        Map<String, Object> created = create(payload);
        String applyId = (String) created.get("id");
        submit(applyId);
        RedFlushApply a = require(applyId);
        a.setRemark("旧接口重定向（退货链路 " + nvl(returnNo) + "），审批通过后请在 9.4.1 登记信息表编号并开票");
        applyDao.updateById(a);

        // 返回视图占位（真红票在审批 + 编号 + 开票后落台账）：沿用退货链路字段承载
        SalesInvoice origin = invoiceDao.selectById(originInvoiceId);
        RedInvoice view = new RedInvoice();
        view.setId(applyId);
        view.setRedNo("待开具-" + a.getApplyNo());
        view.setOriginInvoiceId(originId(origin));
        view.setOriginInvoiceNo(origin == null ? null : origin.getInvoiceNo());
        view.setReturnId(returnId);
        view.setReturnNo(returnNo);
        view.setCustomerId(origin == null ? null : origin.getCustomerId());
        view.setCustomerName(origin == null ? null : origin.getCustomerName());
        view.setRedDate(LocalDate.now());
        view.setAmount(amount);
        view.setReason(reason);
        view.setStatus("PENDING");
        return view;
    }

    // ================================================================
    // helpers
    // ================================================================

    /** 原票可红冲性（C-4.14-12 / BR-4.14-28 共用） */
    static void assertRedFlushable(SalesInvoice origin) {
        if (origin == null) {
            throw new ServiceException(422, "无有效蓝字发票（原票不存在，BR-4.14-28）");
        }
        if (SalesInvoice.ST_VOID.equals(origin.getStatus())) {
            throw new ServiceException(422, "无有效蓝字发票（原票已作废，BR-4.14-28/C-4.14-12）");
        }
        if (SalesInvoice.ST_RED_FULL.equals(origin.getStatus())
                || nvl(origin.getAmount()).subtract(nvl(origin.getRedAmount())).signum() <= 0) {
            throw new ServiceException(422, "原票已全额红冲，不可再发起红冲（C-4.14-12）");
        }
    }

    private RedFlushApply require(String applyId) {
        RedFlushApply a = applyDao.selectById(applyId);
        if (a == null) {
            throw new ServiceException(404, "红冲申请单不存在：" + applyId);
        }
        return a;
    }

    private String currentPeriod() {
        Object p = finPeriodService.current().get("period");
        return p == null ? LocalDate.now().format(PERIOD_FMT) : String.valueOf(p);
    }

    private String originId(SalesInvoice origin) {
        return origin == null ? null : origin.getId();
    }

    private String nextNo(String kind, PrefixQuery query) {
        String prefix = kind + LocalDate.now().format(DAY) + "-";
        int max = 0;
        List<String> nos = query.byPrefix(prefix + "%");
        for (String no : nos) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignore) {
                // 种子/非规范编号跳过
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private interface PrefixQuery {
        List<String> byPrefix(String prefix);
    }

    private String currentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }

    private static BigDecimal netOf(BigDecimal amount, BigDecimal rate) {
        if (rate == null || rate.signum() == 0) {
            return nvl(amount).setScale(2, RoundingMode.HALF_UP);
        }
        return nvl(amount).divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String nvl(String v) {
        return v == null ? "" : v;
    }

    private static String strip(BigDecimal v) {
        return nvl(v).stripTrailingZeros().toPlainString();
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static BigDecimal dec(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (Exception e) {
            throw new ServiceException(422, "金额格式非法：" + o);
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
