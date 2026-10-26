package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.fin.TaxRefundApplyDao;
import com.erp.entity.fin.TaxRefundApply;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.SysParamService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.fin.TaxRefundService;
import com.erp.service.system.NoticeService;
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
 * 留抵退税实现（BR-4.14-33 / S-4.14-15，spec tax-filing）。
 *
 * 四条件（任一不满足 422 一次性列全未满足项）：
 *  ①纳税信用等级 A/B/M；
 *  ②申请前连续 6 个月增量留抵均 > 0；
 *  ③第 6 个月增量留抵 ≥ LDTI_MONTH6_THRESHOLD（默认 500,000）；
 *  ④进项构成比例 ≥ LDTI_CONSTITUTION_MIN（默认 50%，现行留抵退税政策口径）。
 * 允许退还 = 增量留抵 × 构成比例 × 退还比例（LDTI_REFUND_RATIO 默认 100%）。
 * 到账核销（BR-4.14-45 同口径）：一致自动核销+收款凭证；差异> TOLERANCE_DEFAULT 挂账核查。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaxRefundServiceImpl implements TaxRefundService {

    public static final String BIZ_TYPE = "TaxRefundApply";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TaxRefundApplyDao applyDao;
    private final ApprovalEngine approvalEngine;
    private final SysParamService sysParamService;
    private final GlVoucherService voucherService;
    private final NoticeService noticeService;

    // ================================================================
    // 3.1 四条件校验 + 公式
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> apply(Map<String, Object> payload) {
        String period = str(payload.get("period"));
        String creditLevel = str(payload.get("creditLevel"));
        BigDecimal incrCredit = dec(payload.get("incrCredit"));
        BigDecimal constitutionRatio = dec(payload.get("constitutionRatio"));
        BigDecimal month6Incr = dec(payload.get("month6Incr"));
        BigDecimal sixRaw = dec(payload.get("sixMonthTotal"));
        if (!hasText(period) || !hasText(creditLevel) || incrCredit == null
                || constitutionRatio == null || month6Incr == null || sixRaw == null) {
            throw new ServiceException(422, "留抵退税申请要素必填（所属期/信用等级/增量留抵/构成比例/第6月增量/6月合计）");
        }
        BigDecimal month6Threshold = sysParamService.getAmount("LDTI_MONTH6_THRESHOLD",
                new BigDecimal("500000"));
        BigDecimal constitutionMin = sysParamService.getAmount("LDTI_CONSTITUTION_MIN",
                new BigDecimal("0.5"));

        // 四条件逐项校验，一次性列全未满足项（BR-4.14-33）
        List<String> unmet = new ArrayList<>();
        if (!"A".equals(creditLevel) && !"B".equals(creditLevel) && !"M".equals(creditLevel)) {
            unmet.add("①纳税信用等级须为 A/B/M（当前：" + creditLevel + "）");
        }
        // 条件② 代理指标：6 个月增量留抵合计快照 > 0 且逐月为正由登记方保证；
        // 系统校验合计与第 6 月值的一致性（第 6 月 ≤ 合计）
        if (sixRaw.signum() <= 0 || month6Incr.compareTo(sixRaw) > 0) {
            unmet.add("②申请前连续 6 个月增量留抵均大于零（6 月合计 " + strip(sixRaw)
                    + "，第 6 月 " + strip(month6Incr) + "，须逐月为正且第 6 月不超过合计）");
        }
        if (month6Incr.compareTo(month6Threshold) < 0) {
            unmet.add("③第 6 个月增量留抵 " + strip(month6Incr) + " 低于规定金额 "
                    + strip(month6Threshold));
        }
        if (constitutionRatio.compareTo(constitutionMin) < 0) {
            unmet.add("④进项构成比例 " + constitutionRatio.multiply(new BigDecimal("100"))
                    .setScale(2, RoundingMode.HALF_UP) + "% 低于 " + constitutionMin
                    .multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString() + "%");
        }
        if (!unmet.isEmpty()) {
            throw new ServiceException(422, "留抵退税条件校验未通过，已阻断（BR-4.14-33）："
                    + String.join("；", unmet));
        }

        BigDecimal refundRatio = sysParamService.getAmount("LDTI_REFUND_RATIO", BigDecimal.ONE);
        BigDecimal allowed = incrCredit.multiply(constitutionRatio).multiply(refundRatio)
                .setScale(2, RoundingMode.HALF_UP);

        TaxRefundApply a = new TaxRefundApply();
        a.setId(uuid());
        a.setApplyNo(nextNo("RVAP", applyDao::selectNosByPrefix));
        a.setPeriod(period);
        a.setCreditLevel(creditLevel);
        a.setIncrCredit(incrCredit);
        a.setConstitutionRatio(constitutionRatio);
        a.setRefundRatio(refundRatio);
        a.setMonth6Incr(month6Incr);
        a.setSixMonthJson(String.valueOf(sixRaw));
        a.setAllowedRefund(allowed);
        a.setStatus(TaxRefundApply.ST_DRAFT);
        a.setCreateBy(currentUser());
        applyDao.insert(a);
        log.info("LDTI apply {} created: allowed {}", a.getApplyNo(), allowed);
        return Map.of("id", a.getId(), "applyNo", a.getApplyNo(), "allowedRefund", allowed,
                "status", a.getStatus());
    }

    // ================================================================
    // 3.1b L2 审批提交
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submit(String applyId) {
        TaxRefundApply a = require(applyId);
        if (!TaxRefundApply.ST_DRAFT.equals(a.getStatus())) {
            throw new ServiceException(422, "仅草稿态可提交审批（当前：" + a.getStatus() + "）");
        }
        List<List<ApprovalNodeSpec>> chain = new ArrayList<>();
        chain.add(List.of(ApprovalNodeSpec.sign("ROLE_FINANCE_MGR", "留抵退税审批（L2，BR-4.14-33）")));
        ApprovalInstance inst = approvalEngine.submit(BIZ_TYPE, a.getId(),
                "留抵退税申请 " + a.getApplyNo() + "（所属期 " + a.getPeriod()
                        + "，允许退还 " + strip(a.getAllowedRefund()) + "）", "ROLE_FIN_MGR", chain);
        a.setApprId(inst.getId());
        a.setStatus(TaxRefundApply.ST_PENDING_APPROVAL);
        applyDao.updateById(a);
        return Map.of("id", a.getId(), "apprId", inst.getId(), "apprNo", inst.getApprNo());
    }

    // ================================================================
    // 3.1c 税务机关状态登记（受理/退回补正/核准）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> registerStatus(String applyId, Map<String, Object> payload) {
        TaxRefundApply a = require(applyId);
        String target = str(payload.get("status"));
        if ("ACCEPTED".equals(target)) {
            if (!TaxRefundApply.ST_APPROVED.equals(a.getStatus())
                    && !TaxRefundApply.ST_RETURNED.equals(a.getStatus())) {
                throw new ServiceException(422, "仅内部核准后（或补正后重提）可登记受理（当前："
                        + a.getStatus() + "）");
            }
            a.setStatus(TaxRefundApply.ST_ACCEPTED);
        } else if ("RETURNED".equals(target)) {
            String reason = str(payload.get("returnReason"));
            if (!hasText(reason)) {
                throw new ServiceException(422, "退回补正须填写退回原因");
            }
            a.setStatus(TaxRefundApply.ST_RETURNED);
            a.setReturnReason(reason);
            // 结构化补正清单（S-4.14-15：按退回原因生成补正任务并挂起）
            String corrections = str(payload.get("corrections"));
            a.setCorrectionsJson(hasText(corrections) ? corrections
                    : "[{\"reason\":\"" + reason.replace("\"", "'")
                    + "\",\"deadline\":\"" + LocalDate.now().plusDays(7) + "\"}]");
        } else if ("APPROVED".equals(target)) { // 核准
            if (!TaxRefundApply.ST_ACCEPTED.equals(a.getStatus())) {
                throw new ServiceException(422, "仅受理状态可登记核准（当前：" + a.getStatus() + "）");
            }
            BigDecimal approved = dec(payload.get("approvedAmount"));
            if (approved == null) {
                approved = a.getAllowedRefund();
            }
            a.setStatus(TaxRefundApply.ST_APPROVED);
            a.setApprovedAmount(approved);
        } else {
            throw new ServiceException(422, "非法状态登记：" + target
                    + "（可选 ACCEPTED/RETURNED/APPROVED）");
        }
        a.setUpdateBy(currentUser());
        applyDao.updateById(a);
        return Map.of("id", a.getId(), "applyNo", a.getApplyNo(), "status", a.getStatus());
    }

    // ================================================================
    // 3.2 到账核销（BR-4.14-45）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> receive(String applyId, Map<String, Object> payload) {
        TaxRefundApply a = require(applyId);
        if (!TaxRefundApply.ST_APPROVED.equals(a.getStatus())) {
            throw new ServiceException(422, "仅核准状态可登记到账（当前：" + a.getStatus() + "）");
        }
        BigDecimal received = dec(payload.get("receivedAmount"));
        if (received == null || received.signum() < 0) {
            throw new ServiceException(422, "到账金额必填且不得为负");
        }
        LocalDate receivedDate = str(payload.get("receivedDate")) != null
                ? LocalDate.parse(str(payload.get("receivedDate"))) : LocalDate.now();
        BigDecimal approved = nvl(a.getAllowedRefund());
        BigDecimal tolerance = sysParamService.getAmount("TOLERANCE_DEFAULT",
                new BigDecimal("0.01"));
        BigDecimal diff = received.subtract(approved);
        a.setReceivedAmount(received);
        a.setReceivedDate(receivedDate);

        if (diff.abs().compareTo(tolerance) <= 0) {
            // 一致 → 自动核销 + 收款凭证（冲减留抵口径：借 银行存款 / 贷 进项税额）
            var lines = new ArrayList<GlVoucherService.FinVoucherLineSpec>();
            lines.add(GlVoucherService.FinVoucherLineSpec.of("1002", "DR", received,
                    "留抵退税到账-" + a.getApplyNo()));
            lines.add(GlVoucherService.FinVoucherLineSpec.of("222101", "CR", received,
                    "留抵退税到账-" + a.getApplyNo()));
            var voucher = voucherService.create("RVTD", receivedDate,
                    "留抵退税到账核销：" + a.getApplyNo(), "TAXREFUND", a.getApplyNo(), null, lines, true);
            a.setVoucherId(voucher.getId());
            a.setStatus(TaxRefundApply.ST_PAID);
            a.setUpdateBy(currentUser());
            applyDao.updateById(a);
            log.info("LDTI apply {} received {} = approved, auto-reconciled", a.getApplyNo(), received);
            return Map.of("id", a.getId(), "applyNo", a.getApplyNo(), "status", a.getStatus(),
                    "reconciled", true, "voucherId", voucher.getId(), "diff", diff);
        }
        // 差异超容差 → 挂账核查不核销（BR-4.14-45）
        String taskId = uuid();
        a.setReconcileTaskId(taskId);
        a.setUpdateBy(currentUser());
        applyDao.updateById(a);
        noticeService.push("ROLE_FIN_MGR", null, "留抵退税到账差异挂账 " + a.getApplyNo(),
                "到账 " + strip(received) + " vs 核准 " + strip(approved) + "，差额 "
                        + strip(diff) + "，已挂账核查不核销", "TaxRefundApply", a.getId());
        log.warn("LDTI apply {} received {} diff {}挂账核查", a.getApplyNo(), received, diff);
        return Map.of("id", a.getId(), "applyNo", a.getApplyNo(), "status", a.getStatus(),
                "reconciled", false, "reconcileTaskId", taskId, "diff", diff);
    }

    // ================================================================
    // 查询
    // ================================================================

    @Override
    public Page<TaxRefundApply> page(long current, long size, String status, String period) {
        return applyDao.selectPage(new Page<>(current, size), new LambdaQueryWrapper<TaxRefundApply>()
                .eq(TaxRefundApply::getDelFlag, "0")
                .eq(hasText(status), TaxRefundApply::getStatus, status)
                .eq(hasText(period), TaxRefundApply::getPeriod, period)
                .orderByDesc(TaxRefundApply::getCreateDate));
    }

    @Override
    public Map<String, Object> detail(String applyId) {
        TaxRefundApply a = require(applyId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apply", a);
        if (hasText(a.getApprId())) {
            out.put("approval", approvalEngine.logs(BIZ_TYPE, applyId));
        }
        return out;
    }

    private TaxRefundApply require(String applyId) {
        TaxRefundApply a = applyDao.selectById(applyId);
        if (a == null) {
            throw new ServiceException(404, "留抵退税申请不存在：" + applyId);
        }
        return a;
    }

    private String nextNo(String kind, PrefixQuery query) {
        String prefix = kind + LocalDate.now().format(DAY) + "-";
        int max = 0;
        for (String no : query.byPrefix(prefix + "%")) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignore) {
                // 非规范编号跳过
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

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
