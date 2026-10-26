package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.fin.ArInvoiceDao;
import com.erp.dao.fin.ArProvisionDao;
import com.erp.dao.fin.FinAccountDao;
import com.erp.entity.fin.ArInvoice;
import com.erp.entity.fin.ArProvision;
import com.erp.entity.fin.FinAccount;
import com.erp.entity.fin.FinVoucher;
import com.erp.service.SysParamService;
import com.erp.service.fin.ArAgingService;
import com.erp.service.fin.ArConfirmService;
import com.erp.service.fin.ArProvisionService;
import com.erp.service.fin.GlVoucherService;
import com.erp.util.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 应收管理（add-ar-management：8.2.1 确认 / 8.2.2 账龄 / 8.2.3 坏账计提）。
 *
 * 确认标记=账龄起算锚点（未确认不计账龄）；账龄基数=已确认且未核销完（AMOUNT−PAID−RED>0），
 * 按 DUE_DATE 与基准日差值落段（参数 AR_AGING_BUCKETS）；坏账按组合比例法（AR_BAD_DEBT_RATES），
 * 走 trial→confirm→下期自动冲回再重提（BR-4.6-25 语义）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArAgingServiceImpl implements ArConfirmService, ArAgingService, ArProvisionService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCT_PROVISION = "1232";   // 坏账准备
    private static final String ACCT_LOSS = "6702";        // 资产减值损失
    private static final int BATCH_LIMIT = 200;

    private final ArInvoiceDao arDao;
    private final ArProvisionDao provisionDao;
    private final FinAccountDao accountDao;
    private final SysParamService sysParamService;
    private final GlVoucherService voucherService;

    // ================= 8.2.1 应收确认 =================

    @Override
    public Page<ArInvoice> confirmPage(long current, long size, boolean confirmed, String keyword) {
        LambdaQueryWrapper<ArInvoice> w = new LambdaQueryWrapper<ArInvoice>()
                .eq(ArInvoice::getConfirmFlag, confirmed ? "1" : "0")
                .eq(ArInvoice::getDelFlag, "0")
                .like(hasText(keyword), ArInvoice::getArNo, keyword)
                .like(hasText(keyword), ArInvoice::getCustomerName, keyword)
                .orderByDesc(ArInvoice::getInvoiceDate);
        return arDao.selectPage(new Page<>(current, size), w);
    }

    @Override
    @Transactional
    public Map<String, Object> confirm(String arId) {
        requireAny("确认应收", "ROLE_FIN_MGR", "ROLE_FIN_ASSET");
        ArInvoice ar = arDao.selectById(arId);
        if (ar == null || "1".equals(ar.getDelFlag())) {
            throw new ServiceException(404, "应收单不存在：" + arId);
        }
        if ("1".equals(ar.getConfirmFlag())) {
            throw new ServiceException(422, "应收单 " + ar.getArNo() + " 已确认，不可重复确认");
        }
        ArInvoice patch = new ArInvoice();
        patch.setId(ar.getId());
        patch.setConfirmFlag("1");
        patch.setConfirmBy(SecurityUtils.getCurrentUserId());
        patch.setConfirmDate(LocalDateTime.now());
        arDao.updateById(patch);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("arId", ar.getId());
        out.put("arNo", ar.getArNo());
        out.put("confirmFlag", "1");
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> batchConfirm(List<String> arIds) {
        requireAny("批量确认应收", "ROLE_FIN_MGR", "ROLE_FIN_ASSET");
        if (arIds == null || arIds.isEmpty()) {
            throw new ServiceException(422, "请选择待确认应收");
        }
        if (arIds.size() > BATCH_LIMIT) {
            throw new ServiceException(422, "单批确认上限 " + BATCH_LIMIT + " 张，请分批（当前 " + arIds.size() + "）");
        }
        List<String> ok = new ArrayList<>();
        List<String> skip = new ArrayList<>();
        for (String id : arIds) {
            try {
                confirmQuiet(id);
                ok.add(id);
            } catch (ServiceException e) {
                skip.add(id + ":" + e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("confirmed", ok.size());
        out.put("skipped", skip);
        return out;
    }

    /** 批量确认内部（复用单笔逻辑但不重复鉴权——批量入口已鉴权） */
    private void confirmQuiet(String arId) {
        ArInvoice ar = arDao.selectById(arId);
        if (ar == null || "1".equals(ar.getDelFlag())) {
            throw new ServiceException(404, "不存在");
        }
        if ("1".equals(ar.getConfirmFlag())) {
            throw new ServiceException(422, "已确认");
        }
        ArInvoice patch = new ArInvoice();
        patch.setId(ar.getId());
        patch.setConfirmFlag("1");
        patch.setConfirmBy(SecurityUtils.getCurrentUserId());
        patch.setConfirmDate(LocalDateTime.now());
        arDao.updateById(patch);
    }

    // ================= 8.2.2 账龄分析 =================

    @Override
    public Map<String, Object> aging(String customerId, LocalDate baseDate) {
        if (baseDate == null) {
            baseDate = LocalDate.now();
        }
        List<Integer> bounds = bucketBounds();
        int n = bounds.size() + 1;
        List<ArInvoice> open = openConfirmed(customerId);

        BigDecimal[] curr = new BigDecimal[n];
        for (int i = 0; i < n; i++) {
            curr[i] = BigDecimal.ZERO;
        }
        Map<String, Map<String, BigDecimal>> custBucket = new LinkedHashMap<>();
        Map<String, String> custName = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ArInvoice ar : open) {
            BigDecimal bal = balanceOf(ar);
            int b = bucketIndex(overdueDays(ar, baseDate), bounds);
            curr[b] = curr[b].add(bal);
            total = total.add(bal);
            custBucket.computeIfAbsent(ar.getCustomerId(), k -> {
                Map<String, BigDecimal> init = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) {
                    init.put(String.valueOf(i), BigDecimal.ZERO);
                }
                return init;
            });
            Map<String, BigDecimal> cb = custBucket.get(ar.getCustomerId());
            cb.put(String.valueOf(b), cb.get(String.valueOf(b)).add(bal));
            custName.putIfAbsent(ar.getCustomerId(), ar.getCustomerName());
        }

        // 超 90 天占比（BR-4.3-14 判定源）：边界含 90 的段之后的段全属超 90
        int over90From = firstBucketAfter(bounds, 90);
        BigDecimal over90 = BigDecimal.ZERO;
        for (int i = over90From; i < n; i++) {
            over90 = over90.add(curr[i]);
        }
        BigDecimal over90Ratio = total.signum() == 0 ? BigDecimal.ZERO
                : over90.divide(total, 4, RoundingMode.HALF_UP);

        // 环比近似：按当前核销状态回看上期末分段（design D2 时点快照近似口径）
        LocalDate prevBase = baseDate.withDayOfMonth(1).minusDays(1);
        BigDecimal[] prev = new BigDecimal[n];
        for (int i = 0; i < n; i++) {
            prev[i] = BigDecimal.ZERO;
        }
        for (ArInvoice ar : open) {
            prev[bucketIndex(overdueDays(ar, prevBase), bounds)] =
                    prev[bucketIndex(overdueDays(ar, prevBase), bounds)].add(balanceOf(ar));
        }

        List<Map<String, Object>> buckets = new ArrayList<>();
        List<Map<String, Object>> mom = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("label", bucketLabel(i, bounds));
            b.put("base", curr[i].setScale(2, RoundingMode.HALF_UP));
            b.put("ratio", total.signum() == 0 ? BigDecimal.ZERO
                    : curr[i].divide(total, 4, RoundingMode.HALF_UP));
            buckets.add(b);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bucket", bucketLabel(i, bounds));
            m.put("prev", prev[i].setScale(2, RoundingMode.HALF_UP));
            m.put("curr", curr[i].setScale(2, RoundingMode.HALF_UP));
            m.put("delta", curr[i].subtract(prev[i]).setScale(2, RoundingMode.HALF_UP));
            mom.add(m);
        }

        List<Map<String, Object>> customers = new ArrayList<>();
        for (Map.Entry<String, Map<String, BigDecimal>> e : custBucket.entrySet()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("customerId", e.getKey());
            c.put("customerName", custName.get(e.getKey()));
            BigDecimal cTotal = BigDecimal.ZERO;
            BigDecimal cOver90 = BigDecimal.ZERO;
            Map<String, BigDecimal> seg = e.getValue();
            for (int i = 0; i < n; i++) {
                cTotal = cTotal.add(seg.get(String.valueOf(i)));
                if (i >= over90From) {
                    cOver90 = cOver90.add(seg.get(String.valueOf(i)));
                }
            }
            c.put("total", cTotal.setScale(2, RoundingMode.HALF_UP));
            c.put("over90Ratio", cTotal.signum() == 0 ? BigDecimal.ZERO
                    : cOver90.divide(cTotal, 4, RoundingMode.HALF_UP));
            c.put("buckets", seg);
            customers.add(c);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseDate", baseDate.toString());
        out.put("total", total.setScale(2, RoundingMode.HALF_UP));
        out.put("over90", over90.setScale(2, RoundingMode.HALF_UP));
        out.put("over90Ratio", over90Ratio);
        out.put("buckets", buckets);
        out.put("customers", customers);
        out.put("mom", mom);
        return out;
    }

    @Override
    public List<Map<String, Object>> bucketBases(String customerId, LocalDate baseDate) {
        List<Integer> bounds = bucketBounds();
        List<BigDecimal> rates = bucketRates(bounds.size() + 1);
        int n = bounds.size() + 1;
        BigDecimal[] base = new BigDecimal[n];
        for (int i = 0; i < n; i++) {
            base[i] = BigDecimal.ZERO;
        }
        for (ArInvoice ar : openConfirmed(customerId)) {
            base[bucketIndex(overdueDays(ar, baseDate), bounds)] =
                    base[bucketIndex(overdueDays(ar, baseDate), bounds)].add(balanceOf(ar));
        }
        List<Map<String, Object>> segs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("bucket", bucketLabel(i, bounds));
            s.put("base", base[i].setScale(2, RoundingMode.HALF_UP));
            s.put("rate", rates.get(i));
            s.put("accrual", base[i].multiply(rates.get(i)).setScale(2, RoundingMode.HALF_UP));
            segs.add(s);
        }
        return segs;
    }

    // ================= 8.2.3 坏账计提 =================

    @Override
    public Map<String, Object> trial(String customerId, String period) {
        requireAny("坏账计提试算", "ROLE_FIN_MGR", "ROLE_FIN_ASSET");
        if (!hasText(customerId) || !hasText(period) || period.length() != 6) {
            throw new ServiceException(422, "客户与期间（yyyyMM）必填");
        }
        LocalDate periodEnd = periodEnd(period);
        List<Map<String, Object>> segs = bucketBases(customerId, periodEnd);
        BigDecimal target = BigDecimal.ZERO;
        for (Map<String, Object> s : segs) {
            target = target.add((BigDecimal) s.get("accrual"));
        }
        BigDecimal booked = bookedBalance(customerId, period);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customerId", customerId);
        out.put("period", period);
        out.put("segs", segs);
        out.put("target", target.setScale(2, RoundingMode.HALF_UP));
        out.put("booked", booked.setScale(2, RoundingMode.HALF_UP));
        out.put("periodAccrual", target.subtract(booked).setScale(2, RoundingMode.HALF_UP));
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> confirmProvision(String customerId, String period) {
        requireAny("坏账计提确认", "ROLE_FIN_MGR", "ROLE_FIN_ASSET");
        if (!hasText(customerId) || !hasText(period) || period.length() != 6) {
            throw new ServiceException(422, "客户与期间（yyyyMM）必填");
        }
        // ① 科目断言（BR-4.13-33 模式）
        FinAccount acctProvision = accountDao.selectActiveByCode(ACCT_PROVISION);
        FinAccount acctLoss = accountDao.selectActiveByCode(ACCT_LOSS);
        List<String> missing = new ArrayList<>();
        if (acctProvision == null) {
            missing.add(ACCT_PROVISION + " 坏账准备");
        }
        if (acctLoss == null) {
            missing.add(ACCT_LOSS + " 资产减值损失");
        }
        if (!missing.isEmpty()) {
            throw new ServiceException(422, "坏账科目缺失/停用：" + String.join("、", missing));
        }
        // 幂等：该期该客户已确认 → 422
        if (provisionDao.selectConfirmed(period, customerId) != null) {
            throw new ServiceException(422, "期间 " + period + " 该客户已确认计提，不可重复");
        }
        LocalDate periodEnd = periodEnd(period);
        List<Map<String, Object>> segs = bucketBases(customerId, periodEnd);
        BigDecimal target = BigDecimal.ZERO;
        for (Map<String, Object> s : segs) {
            target = target.add((BigDecimal) s.get("accrual"));
        }
        // ② 冲回上期未冲回计提（BR-4.6-25 语义）
        ArProvision prev = provisionDao.selectUnreversedPrev(customerId, period);
        BigDecimal booked = BigDecimal.ZERO;
        String reversedVoucherId = null;
        if (prev != null && hasText(prev.getVoucherId())) {
            FinVoucher rev = voucherService.reverse(prev.getVoucherId(),
                    "坏账计提自动冲回（" + period + "）", null);
            reversedVoucherId = rev.getId();
            booked = nz(prev.getTargetBalance());
            ArProvision ppatch = new ArProvision();
            ppatch.setId(prev.getId());
            ppatch.setReversedFlag("1");
            ppatch.setReversedVoucherId(rev.getId());
            provisionDao.updateById(ppatch);
        }
        BigDecimal net = target.subtract(booked);
        // ③ 本期全额重提（借 6702 / 贷 1232）
        String provisionNo = nextProvisionNo(period, customerId);
        String voucherId = null;
        if (target.signum() != 0) {
            String summary = "坏账计提（" + period + "）";
            FinVoucher v = voucherService.create("PVD", periodEnd, summary,
                    "AR_PROVISION", provisionNo, null, List.of(
                            GlVoucherService.FinVoucherLineSpec.of(ACCT_LOSS, "DR", target, summary),
                            GlVoucherService.FinVoucherLineSpec.of(ACCT_PROVISION, "CR", target, summary)));
            voucherId = v.getId();
        }
        // ④ 台账 CONFIRMED 落库（六段快照 + 凭证锚）
        ArProvision p = new ArProvision();
        p.setId(uuid());
        p.setProvisionNo(provisionNo);
        p.setPeriod(period);
        p.setCustomerId(customerId);
        ArInvoice probe = arDao.selectOne(new LambdaQueryWrapper<ArInvoice>()
                .eq(ArInvoice::getCustomerId, customerId).last("LIMIT 1"));
        if (probe != null) {
            p.setCustomerCode(probe.getCustomerCode());
            p.setCustomerName(probe.getCustomerName());
        }
        p.setBucketBase(toJson(segs));
        p.setTargetBalance(target.setScale(2, RoundingMode.HALF_UP));
        p.setBookedBalance(booked.setScale(2, RoundingMode.HALF_UP));
        p.setPeriodAccrual(net.setScale(2, RoundingMode.HALF_UP));
        p.setReversedFlag("0");
        p.setReversedVoucherId(reversedVoucherId);
        p.setVoucherId(voucherId);
        p.setStatus(ArProvision.ST_CONFIRMED);
        p.setCreateBy(SecurityUtils.getCurrentUserId());
        provisionDao.insert(p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provisionId", p.getId());
        out.put("provisionNo", provisionNo);
        out.put("target", p.getTargetBalance());
        out.put("booked", p.getBookedBalance());
        out.put("periodAccrual", p.getPeriodAccrual());
        out.put("voucherId", voucherId);
        out.put("reversedVoucherId", reversedVoucherId);
        return out;
    }

    @Override
    public Page<ArProvision> provisionPage(long current, long size, String period, String customerId) {
        LambdaQueryWrapper<ArProvision> w = new LambdaQueryWrapper<ArProvision>()
                .eq(ArProvision::getDelFlag, "0")
                .eq(hasText(period), ArProvision::getPeriod, period)
                .eq(hasText(customerId), ArProvision::getCustomerId, customerId)
                .orderByDesc(ArProvision::getPeriod);
        return provisionDao.selectPage(new Page<>(current, size), w);
    }

    @Override
    public Map<String, Object> provisionDetail(String provisionId) {
        ArProvision p = provisionDao.selectById(provisionId);
        if (p == null) {
            throw new ServiceException(404, "计提台账不存在：" + provisionId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provision", p);
        try {
            out.put("segs", MAPPER.readValue(p.getBucketBase(), List.class));
        } catch (Exception ignore) {
            out.put("segs", List.of());
        }
        return out;
    }

    // ================= 私有助手 =================

    /** 已确认且未核销完（AMOUNT−PAID−RED>0）的应收 */
    private List<ArInvoice> openConfirmed(String customerId) {
        List<ArInvoice> all = arDao.selectList(new LambdaQueryWrapper<ArInvoice>()
                .eq(ArInvoice::getConfirmFlag, "1")
                .eq(ArInvoice::getDelFlag, "0")
                .eq(hasText(customerId), ArInvoice::getCustomerId, customerId));
        List<ArInvoice> out = new ArrayList<>();
        for (ArInvoice ar : all) {
            if (balanceOf(ar).signum() > 0) {
                out.add(ar);
            }
        }
        return out;
    }

    /** 已提余额：该期前未冲回 CONFIRMED 台账的 target 之和（每次只冲上一期，唯一未冲回） */
    private BigDecimal bookedBalance(String customerId, String period) {
        List<ArProvision> rows = provisionDao.selectList(new LambdaQueryWrapper<ArProvision>()
                .eq(ArProvision::getCustomerId, customerId)
                .eq(ArProvision::getStatus, ArProvision.ST_CONFIRMED)
                .eq(ArProvision::getReversedFlag, "0")
                .eq(ArProvision::getDelFlag, "0")
                .lt(ArProvision::getPeriod, period));
        BigDecimal sum = BigDecimal.ZERO;
        for (ArProvision r : rows) {
            sum = sum.add(nz(r.getTargetBalance()));
        }
        return sum;
    }

    private BigDecimal balanceOf(ArInvoice ar) {
        return nz(ar.getAmount()).subtract(nz(ar.getPaidAmount())).subtract(nz(ar.getRedAmount()));
    }

    private long overdueDays(ArInvoice ar, LocalDate baseDate) {
        if (ar.getDueDate() == null) {
            return 0;
        }
        return java.time.temporal.ChronoUnit.DAYS.between(ar.getDueDate(), baseDate);
    }

    /** 落段：days<=0 → 0 段；否则第一个 >= days 的边界所在段 */
    private int bucketIndex(long days, List<Integer> bounds) {
        if (days <= 0) {
            return 0;
        }
        for (int i = 0; i < bounds.size(); i++) {
            if (days <= bounds.get(i)) {
                return i;
            }
        }
        return bounds.size();
    }

    /** 含 value 的段之后的第一段下标（超 90 起点） */
    private int firstBucketAfter(List<Integer> bounds, int value) {
        for (int i = 0; i < bounds.size(); i++) {
            if (bounds.get(i) >= value) {
                return i + 1;
            }
        }
        return bounds.size();
    }

    private String bucketLabel(int idx, List<Integer> bounds) {
        if (idx == 0) {
            return "0-" + bounds.get(0);
        }
        if (idx == bounds.size()) {
            return bounds.get(bounds.size() - 1) + "+";
        }
        return (bounds.get(idx - 1) + 1) + "-" + bounds.get(idx);
    }

    /** 解析 AR_AGING_BUCKETS（逗号分隔天数）→ 边界列表 */
    private List<Integer> bucketBounds() {
        String raw = sysParamService.getValue("AR_AGING_BUCKETS", "30,60,90,180,360");
        List<Integer> bounds = new ArrayList<>();
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                bounds.add(Integer.parseInt(t));
            }
        }
        if (bounds.isEmpty()) {
            throw new ServiceException(500, "AR_AGING_BUCKETS 参数无效");
        }
        return bounds;
    }

    /** 解析 AR_BAD_DEBT_RATES（段数须匹配；缺省回退等差默认） */
    private List<BigDecimal> bucketRates(int n) {
        String raw = sysParamService.getValue("AR_BAD_DEBT_RATES", "0.005,0.01,0.02,0.05,0.10,0.30");
        List<BigDecimal> rates = new ArrayList<>();
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                rates.add(new BigDecimal(t));
            }
        }
        if (rates.size() != n) {
            throw new ServiceException(422, "AR_BAD_DEBT_RATES 段数(" + rates.size()
                    + ")与 AR_AGING_BUCKETS 段数(" + n + ")不一致");
        }
        return rates;
    }

    private LocalDate periodEnd(String period) {
        int y = Integer.parseInt(period.substring(0, 4));
        int m = Integer.parseInt(period.substring(4, 6));
        LocalDate start = LocalDate.of(y, m, 1);
        return start.withDayOfMonth(start.lengthOfMonth());
    }

    private String nextProvisionNo(String period, String customerId) {
        String base = "PVD" + period + "-" + customerId.replaceAll("[^A-Za-z0-9]", "");
        String prefix = base + "-%";
        int max = 0;
        for (String no : provisionDao.selectNosByPrefix(prefix)) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(base.length() + 1)));
            } catch (Exception ignore) {
                // 跳过非数字后缀
            }
        }
        return base + "-" + String.format("%02d", max + 1);
    }

    private String toJson(List<Map<String, Object>> segs) {
        try {
            return MAPPER.writeValueAsString(segs);
        } catch (Exception e) {
            throw new ServiceException(500, "段快照序列化失败");
        }
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void requireAny(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> roles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        if (roles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
            return;
        }
        for (String want : allowed) {
            for (String r : roles) {
                if (want.equalsIgnoreCase(r)) {
                    return;
                }
            }
        }
        throw new ServiceException(403, "无权" + action);
    }
}
