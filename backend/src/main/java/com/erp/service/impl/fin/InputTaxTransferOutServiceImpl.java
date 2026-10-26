package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.InputInvoiceDao;
import com.erp.entity.fin.InputInvoice;
import com.erp.service.SysParamService;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.fin.InputTaxTransferOutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 进项税额转出实现（M9-4-3，spec invoice-management ADDED，design D4/D5）。
 *
 * 卡控：仅 CERTIFIED 可转出；单票累计转出 ≤ 已抵扣税额（剩余可转出 = taxAmount − transferOutAmount）；
 * 凭证失败 422 阻断（DRAFT 制单≠审核）；费用科目走 SysParam `TAX_TRANSFER_OUT_ACCOUNT`
 * （默认 660299 管理费用-其他（简易计税不得抵扣）），转出税额科目 222103。
 * 分摊模式：当期 CERTIFIED 票按已抵扣税额比例逐票分摊，尾差调整到最大票。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InputTaxTransferOutServiceImpl implements InputTaxTransferOutService {

    /** 转出税额科目（应交税费-进项税转出） */
    static final String ACCT_TRANSFER_OUT = "222103";
    /** 费用侧默认科目（可 SysParam 覆盖） */
    static final String DEFAULT_EXPENSE_ACCT = "660299";

    private final InputInvoiceDao inputDao;
    private final GlVoucherService voucherService;
    private final SysParamService sysParamService;

    // ================================================================
    // 6.1 直接转出
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> directTransfer(Map<String, Object> payload) {
        String invoiceId = str(payload.get("invoiceId"));
        BigDecimal amount = dec(payload.get("amount"));
        String reason = str(payload.get("reason"));
        if (!hasText(invoiceId) || amount == null || !hasText(reason)) {
            throw new ServiceException(422, "转出要素必填（发票/金额/原因）");
        }
        if (amount.signum() <= 0) {
            throw new ServiceException(422, "转出金额必须大于 0");
        }
        InputInvoice inv = inputDao.selectById(invoiceId.trim());
        if (inv == null) {
            throw new ServiceException(404, "进项发票不存在：" + invoiceId);
        }
        if (!InputInvoice.CERT_CERTIFIED.equals(inv.getCertStatus())) {
            throw new ServiceException(422, "仅已认证发票可转出（当前：" + inv.getCertStatus() + "）");
        }
        BigDecimal deductible = nvl(inv.getTaxAmount());
        BigDecimal transferred = nvl(inv.getTransferOutAmount());
        BigDecimal remain = deductible.subtract(transferred);
        if (amount.compareTo(remain) > 0) {
            throw new ServiceException(422, "转出金额超出剩余可转出税额 " + strip(remain));
        }

        // 凭证（借 费用 / 贷 222103 进项税转出，DRAFT）
        var voucher = createTransferVoucher(amount, reason,
                "进项转出-" + inv.getInvoiceNo(), "TAXOUT", inv.getInvoiceNo());

        inv.setTransferOutAmount(transferred.add(amount));
        inputDao.updateById(inv);
        log.info("input tax transfer-out {} amount {} by direct, voucher {}",
                inv.getInvoiceNo(), amount, voucher.getVoucherNo());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("invoiceId", inv.getId());
        out.put("invoiceNo", inv.getInvoiceNo());
        out.put("transferOutAmount", amount);
        out.put("cumulativeTransferOut", inv.getTransferOutAmount());
        out.put("remainBefore", remain);
        out.put("voucherId", voucher.getId());
        out.put("voucherNo", voucher.getVoucherNo());
        return out;
    }

    // ================================================================
    // 6.2 分摊转出（BR-4.14-22 公式）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> apportionTransfer(Map<String, Object> payload) {
        BigDecimal totalInputTax = dec(payload.get("totalInputTax"));
        BigDecimal exemptSales = dec(payload.get("exemptSales"));
        BigDecimal totalSales = dec(payload.get("totalSales"));
        String reason = str(payload.get("reason"));
        String period = str(payload.get("period"));
        if (totalInputTax == null || exemptSales == null || totalSales == null || !hasText(reason)) {
            throw new ServiceException(422, "分摊转出要素必填（全部进项税额/免税简易项目销售额/全部销售额/原因）");
        }
        if (totalInputTax.signum() <= 0 || totalSales.signum() <= 0 || exemptSales.signum() < 0) {
            throw new ServiceException(422, "进项税额与全部销售额须大于 0，免税简易项目销售额不得为负");
        }
        if (exemptSales.compareTo(totalSales) > 0) {
            throw new ServiceException(422, "免税与简易计税项目销售额不得大于全部销售额");
        }
        if (!hasText(period) || !period.matches("\\d{6}")) {
            period = LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
        }

        // 公式：转出税额 = 无法划分的全部进项税额 × (免税简易项目销售额 / 全部销售额)
        BigDecimal ratio = exemptSales.divide(totalSales, 8, RoundingMode.HALF_UP);
        BigDecimal transferTotal = totalInputTax.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        if (transferTotal.signum() <= 0) {
            throw new ServiceException(422, "按公式计算的转出税额为 0，无需转出");
        }

        // 当期 CERTIFIED 发票（按已抵扣税额降序）逐票分摊，尾差调最大票
        List<InputInvoice> certified = inputDao.selectList(new LambdaQueryWrapper<InputInvoice>()
                .eq(InputInvoice::getDelFlag, "0")
                .eq(InputInvoice::getCertStatus, InputInvoice.CERT_CERTIFIED)
                .eq(InputInvoice::getCertPeriod, period)
                .orderByDesc(InputInvoice::getTaxAmount));
        if (certified.isEmpty()) {
            throw new ServiceException(422, "所属期 " + period + " 无已认证进项发票，无法分摊转出");
        }
        BigDecimal poolRemaining = BigDecimal.ZERO;
        for (InputInvoice inv : certified) {
            poolRemaining = poolRemaining.add(nvl(inv.getTaxAmount()))
                    .subtract(nvl(inv.getTransferOutAmount()));
        }
        if (poolRemaining.signum() <= 0) {
            throw new ServiceException(422, "当期已认证进项税额已全部转出，无可分摊余额");
        }
        if (transferTotal.compareTo(poolRemaining) > 0) {
            throw new ServiceException(422, "分摊转出税额 " + strip(transferTotal)
                    + " 超出当期可转出余额 " + strip(poolRemaining));
        }

        // 逐票分摊：按剩余可转出比例分配，尾差（分摊合计与总额之差）调到最大票
        List<Map<String, Object>> detail = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (int i = 0; i < certified.size(); i++) {
            InputInvoice inv = certified.get(i);
            BigDecimal invRemain = nvl(inv.getTaxAmount()).subtract(nvl(inv.getTransferOutAmount()));
            BigDecimal share;
            if (i == certified.size() - 1) {
                // 最后一张（税额最小）承接尾差
                share = transferTotal.subtract(allocated);
            } else {
                share = transferTotal.multiply(invRemain)
                        .divide(poolRemaining, 2, RoundingMode.HALF_UP);
                if (share.compareTo(invRemain) > 0) {
                    share = invRemain;
                }
            }
            allocated = allocated.add(share);
            if (share.signum() <= 0) {
                continue;
            }
            inv.setTransferOutAmount(nvl(inv.getTransferOutAmount()).add(share));
            inputDao.updateById(inv);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("invoiceNo", inv.getInvoiceNo());
            row.put("deductibleTax", inv.getTaxAmount());
            row.put("transferOut", share);
            row.put("cumulativeTransferOut", inv.getTransferOutAmount());
            detail.add(row);
        }
        if (allocated.compareTo(transferTotal) != 0) {
            throw new ServiceException(422, "分摊计算异常：分摊合计 " + strip(allocated)
                    + " ≠ 转出总额 " + strip(transferTotal) + "，已回滚");
        }

        // 汇总凭证（明细快照入摘要）
        StringBuilder snapshot = new StringBuilder();
        for (Map<String, Object> row : detail) {
            if (snapshot.length() > 0) {
                snapshot.append("；");
            }
            snapshot.append(row.get("invoiceNo")).append("=").append(row.get("transferOut"));
        }
        var voucher = createTransferVoucher(transferTotal,
                reason + "（分摊比例 " + ratio.multiply(new BigDecimal("100"))
                        .setScale(2, RoundingMode.HALF_UP) + "%；" + snapshot + "）",
                "进项分摊转出-" + period, "TAXOUT", "APPORTION-" + period);
        log.info("input tax apportion transfer-out period {} total {} ratio {} voucher {}",
                period, transferTotal, ratio, voucher.getVoucherNo());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period);
        out.put("totalInputTax", totalInputTax);
        out.put("exemptSales", exemptSales);
        out.put("totalSales", totalSales);
        out.put("ratio", ratio);
        out.put("transferTotal", transferTotal);
        out.put("invoiceCount", detail.size());
        out.put("detail", detail);
        out.put("voucherId", voucher.getId());
        out.put("voucherNo", voucher.getVoucherNo());
        return out;
    }

    private com.erp.entity.fin.FinVoucher createTransferVoucher(BigDecimal amount, String reason,
                                                                String summary, String sourceType,
                                                                String sourceDocNo) {
        String expenseAcct = sysParamService.getValue("TAX_TRANSFER_OUT_ACCOUNT",
                DEFAULT_EXPENSE_ACCT);
        List<GlVoucherService.FinVoucherLineSpec> lines = new ArrayList<>();
        lines.add(GlVoucherService.FinVoucherLineSpec.of(expenseAcct, "DR", amount, summary));
        lines.add(GlVoucherService.FinVoucherLineSpec.of(ACCT_TRANSFER_OUT, "CR", amount, summary));
        return voucherService.create("TOUT", LocalDate.now(), "进项税额转出：" + reason,
                sourceType, sourceDocNo, null, lines, true);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
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
}
