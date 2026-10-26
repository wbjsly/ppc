package com.erp.service.impl.qms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.qms.CopqEntryDao;
import com.erp.entity.qms.CopqEntry;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.qms.CopqService;
import com.erp.util.SecurityUtils;
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
 * COPQ 实现（spec quality-cost，tasks 9.6~9.8）。
 * 归集四类枚举 + 来源反查；异常 ≥10× 近 3 月月均 → ANOMALY_HOLD（BR-4.12-39）；
 * 财务确认走底座 CopqFinance（BR-4.12-40）；报表草稿/正式分列 + 外部失败 >30% 红标（BR-4.12-41）。
 */
@Slf4j
@Service
public class CopqServiceImpl implements CopqService {

    private static final List<String> CATEGORIES = List.of(
            "PREVENTION", "APPRAISAL", "INTERNAL_FAILURE", "EXTERNAL_FAILURE");
    private static final List<String> SOURCES = List.of("NCR", "RETURN", "SCAR", "SCRAP", "CAPA", "MANUAL");

    private final CopqEntryDao entryDao;
    private final ApprovalEngine approvalEngine;
    /** 系统参数（rework-copq-analysis 3.2：COPQ 目标值，空 = 不参与考核） */
    private final com.erp.service.SysParamService sysParamService;

    public CopqServiceImpl(CopqEntryDao entryDao, ApprovalEngine approvalEngine,
                           com.erp.service.SysParamService sysParamService) {
        this.entryDao = entryDao;
        this.approvalEngine = approvalEngine;
        this.sysParamService = sysParamService;
    }

    // ================= 9.6 归集 =================

    @Override
    @Transactional
    public CopqEntry record(Map<String, Object> body) {
        // 偏差 D2（add-quality-collaboration）——成本类别以枚举直存（CATEGORY），不做「成本科目→会计科目」映射；8.x 落地回补
        String category = str(body.get("category"));
        if (!hasText(category) || !CATEGORIES.contains(category)) {
            throw new ServiceException(422, "成本类别必填（PREVENTION/APPRAISAL/INTERNAL_FAILURE/EXTERNAL_FAILURE）");
        }
        String sourceType = str(body.get("sourceType"));
        if (!hasText(sourceType) || !SOURCES.contains(sourceType)) {
            throw new ServiceException(422, "来源类型必填（NCR/RETURN/SCAR/CAPA/MANUAL）");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(String.valueOf(body.get("amount")));
        } catch (Exception e) {
            throw new ServiceException(422, "金额必须为数字");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new ServiceException(422, "金额必须大于 0");
        }
        String sourceId = str(body.get("sourceId"));

        // 幂等：同来源同类别已有记录（NCR 处置 / 退货出库 可能重复触发）
        if (hasText(sourceId)) {
            CopqEntry existed = entryDao.selectOne(new LambdaQueryWrapper<CopqEntry>()
                    .eq(CopqEntry::getSourceType, sourceType)
                    .eq(CopqEntry::getSourceId, sourceId)
                    .eq(CopqEntry::getCategory, category)
                    .last("LIMIT 1"));
            if (existed != null) {
                return existed;
            }
        }

        CopqEntry e = new CopqEntry();
        e.setEntryNo(nextEntryNo());
        e.setCategory(category);
        e.setAmount(amount);
        e.setSourceType(sourceType);
        e.setSourceId(sourceId);
        e.setSourceNo(str(body.get("sourceNo")));
        e.setNcrId(str(body.get("ncrId")));
        e.setScarId(str(body.get("scarId")));
        e.setItemCode(str(body.get("itemCode")));
        e.setBatchNo(str(body.get("batchNo")));
        e.setSupplierId(str(body.get("supplierId")));
        e.setSupplierName(str(body.get("supplierName")));
        e.setProcessCode(str(body.get("processCode")));
        e.setCustomerName(str(body.get("customerName")));
        e.setUnattributedFlag(hasText(e.getSupplierId()) ? "0" : "1");
        String occur = str(body.get("occurDate"));
        try {
            e.setOccurDate(hasText(occur) ? LocalDate.parse(occur) : LocalDate.now());
        } catch (Exception ex) {
            e.setOccurDate(LocalDate.now());
        }
        e.setRemark(str(body.get("remark")));
        e.setStatus("PENDING_FINANCE");
        e.setAnomalyFlag("0");
        e.setCreateBy(SecurityUtils.getCurrentUserId());

        // ---- 异常检测（BR-4.12-39）：单笔 ≥10× 近 3 月同类别已确认月均 ----
        BigDecimal monthlyAvg = confirmedMonthlyAvg(category);
        if (monthlyAvg != null && monthlyAvg.signum() > 0
                && amount.compareTo(monthlyAvg.multiply(BigDecimal.TEN)) >= 0) {
            e.setAnomalyFlag("1");
            e.setStatus("ANOMALY_HOLD");
            log.warn("COPQ {} 金额异常（{} ≥ 10× 月均 {}），阻断入正式报表待核实（BR-4.12-39）",
                    e.getEntryNo(), amount, monthlyAvg);
        }
        entryDao.insert(e);
        return e;
    }

    @Override
    @Transactional
    public CopqEntry createManual(Map<String, Object> body) {
        requireRole("录入质量成本", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR", "ROLE_ADMIN");
        if (!hasText(str(body.get("sourceType")))) {
            body.put("sourceType", "MANUAL");
        }
        return record(body);
    }

    /** 近 3 月已确认月均（按月求和再平均；无数据返回 null 不判异常） */
    private BigDecimal confirmedMonthlyAvg(String category) {
        LocalDateTime since = LocalDateTime.now().minusDays(95);
        List<CopqEntry> list = entryDao.selectList(new LambdaQueryWrapper<CopqEntry>()
                .eq(CopqEntry::getCategory, category)
                .eq(CopqEntry::getStatus, "CONFIRMED")
                .ge(CopqEntry::getCreateDate, since));
        if (list.isEmpty()) {
            return null;
        }
        Map<String, BigDecimal> byMonth = new LinkedHashMap<>();
        for (CopqEntry e : list) {
            String m = e.getCreateDate() == null ? "-" : e.getCreateDate().format(DateTimeFormatter.ofPattern("yyyy-MM"));
            byMonth.merge(m, nvl(e.getAmount()), BigDecimal::add);
        }
        if (byMonth.isEmpty()) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal v : byMonth.values()) {
            total = total.add(v);
        }
        return total.divide(BigDecimal.valueOf(byMonth.size()), 2, RoundingMode.HALF_UP);
    }

    // ================= 9.7 财务确认 =================

    @Override
    @Transactional
    public CopqEntry submitFinance(String id) {
        requireRole("提交财务确认", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR", "ROLE_ADMIN");
        CopqEntry e = require(id);
        if ("ANOMALY_HOLD".equals(e.getStatus())) {
            throw new ServiceException(422, "金额异常待核（BR-4.12-39），核实通过后方可确认");
        }
        if ("CONFIRMED".equals(e.getStatus())) {
            throw new ServiceException(422, "已确认，不可重复提交");
        }
        if (!"PENDING_FINANCE".equals(e.getStatus())) {
            throw new ServiceException(422, "状态不可确认：" + e.getStatus());
        }
        // 底座 CopqFinance 单签（财务域未建 → ADMIN 代，D2 偏差）
        List<List<ApprovalNodeSpec>> chain = List.of(List.of(
                ApprovalNodeSpec.sign("ROLE_ADMIN", "财务确认（ADMIN 代）")));
        var inst = approvalEngine.submit("CopqFinance", e.getId(),
                "质量成本确认：" + e.getEntryNo() + " / " + e.getCategory()
                        + " / " + e.getAmount(), null, chain);
        e.setApprovalId(inst.getId());
        if (entryDao.updateById(e) == 0) {
            throw new ServiceException(409, "成本单更新冲突");
        }
        return e;
    }

    @Override
    @Transactional
    public CopqEntry resolveAnomaly(String id, boolean pass, String remark) {
        requireRole("异常核实", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR", "ROLE_ADMIN");
        CopqEntry e = require(id);
        if (!"ANOMALY_HOLD".equals(e.getStatus())) {
            throw new ServiceException(422, "非异常待核状态：" + e.getStatus());
        }
        if (pass) {
            e.setStatus("PENDING_FINANCE");
            e.setRemark((hasText(e.getRemark()) ? e.getRemark() + "；" : "") + "核实通过：" + remark);
        } else {
            e.setRemark((hasText(e.getRemark()) ? e.getRemark() + "；" : "") + "核销不予入账：" + remark);
            e.setStatus("CANCELLED");
        }
        entryDao.updateById(e);
        return e;
    }

    // ================= 9.8 查询与报表 =================

    @Override
    public Page<Map<String, Object>> page(long current, long size, String keyword, String category,
                                          String status) {
        LambdaQueryWrapper<CopqEntry> qw = new LambdaQueryWrapper<CopqEntry>()
                .eq(hasText(category), CopqEntry::getCategory, category)
                .eq(hasText(status), CopqEntry::getStatus, status)
                .and(hasText(keyword), w -> w.like(CopqEntry::getEntryNo, keyword)
                        .or().like(CopqEntry::getSourceNo, keyword)
                        .or().like(CopqEntry::getSupplierName, keyword))
                .orderByDesc(CopqEntry::getCreateDate);
        Page<CopqEntry> raw = entryDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CopqEntry e : raw.getRecords()) {
            rows.add(toRow(e));
        }
        out.setRecords(rows);
        return out;
    }

    @Override
    public Map<String, Object> report(String month) {
        // 默认当月
        String period = hasText(month) ? month
                : LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        List<CopqEntry> all = entryDao.selectList(new LambdaQueryWrapper<CopqEntry>()
                .ge(CopqEntry::getOccurDate, LocalDate.parse(period + "-01"))
                .lt(CopqEntry::getOccurDate, LocalDate.parse(period + "-01").plusMonths(1))
                .orderByDesc(CopqEntry::getOccurDate));

        Map<String, BigDecimal> confirmed = new LinkedHashMap<>();
        Map<String, BigDecimal> draft = new LinkedHashMap<>();
        BigDecimal confirmedTotal = BigDecimal.ZERO;
        BigDecimal draftTotal = BigDecimal.ZERO;
        BigDecimal externalConfirmed = BigDecimal.ZERO;
        Map<String, BigDecimal> bySupplier = new LinkedHashMap<>();
        BigDecimal unattributed = BigDecimal.ZERO;

        for (CopqEntry e : all) {
            if ("CONFIRMED".equals(e.getStatus())) {
                confirmed.merge(e.getCategory(), nvl(e.getAmount()), BigDecimal::add);
                confirmedTotal = confirmedTotal.add(nvl(e.getAmount()));
                if ("EXTERNAL_FAILURE".equals(e.getCategory())) {
                    externalConfirmed = externalConfirmed.add(nvl(e.getAmount()));
                }
                if (hasText(e.getSupplierId())) {
                    bySupplier.merge(e.getSupplierName(), nvl(e.getAmount()), BigDecimal::add);
                } else {
                    unattributed = unattributed.add(nvl(e.getAmount()));
                }
            } else if (!"CANCELLED".equals(e.getStatus())) {
                // 未确认（PENDING_FINANCE / ANOMALY_HOLD）→ 草稿单列（BR-4.12-40）
                draft.merge(e.getCategory(), nvl(e.getAmount()), BigDecimal::add);
                draftTotal = draftTotal.add(nvl(e.getAmount()));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period);
        out.put("confirmed", confirmed);
        out.put("confirmedTotal", confirmedTotal);
        out.put("draft", draft);
        out.put("draftTotal", draftTotal);
        out.put("externalFailure", externalConfirmed);
        // 外部失败占比 >30% → 红标 + 改善建议（BR-4.12-41）
        BigDecimal ratio = confirmedTotal.signum() > 0
                ? externalConfirmed.multiply(BigDecimal.valueOf(100))
                        .divide(confirmedTotal, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        out.put("externalRatio", ratio);
        boolean alert = ratio.compareTo(new BigDecimal("30")) > 0;
        out.put("externalAlert", alert);
        out.put("suggestion", alert
                ? "外部失败成本占比 " + ratio + "% 超 30%：建议启动供应商专项改善（SCAR）并纳入月度质量会议题（BR-4.12-41）"
                : null);
        Map<String, Object> bySup = new LinkedHashMap<>();
        bySupplier.forEach((k, v) -> bySup.put(k == null ? "未归因" : k, v));
        bySup.put("未归因", unattributed);
        out.put("bySupplier", bySup);

        // ---- 趋势（rework-copq-analysis 3.1）：近 12 月已确认四类序列，缺月补零 ----
        List<CopqEntry> window = entryDao.selectList(new LambdaQueryWrapper<CopqEntry>()
                .ge(CopqEntry::getOccurDate, LocalDate.parse(period + "-01").minusMonths(11))
                .lt(CopqEntry::getOccurDate, LocalDate.parse(period + "-01").plusMonths(1))
                .eq(CopqEntry::getStatus, "CONFIRMED"));
        Map<String, Map<String, BigDecimal>> byMonth = new LinkedHashMap<>();
        for (CopqEntry e : window) {
            String m = e.getOccurDate().format(DateTimeFormatter.ofPattern("yyyy-MM"));
            byMonth.computeIfAbsent(m, k -> new LinkedHashMap<>())
                    .merge(e.getCategory(), nvl(e.getAmount()), BigDecimal::add);
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        BigDecimal prevTotal = null;
        BigDecimal currTotal = null;
        java.time.YearMonth ym = java.time.YearMonth.parse(period).minusMonths(11);
        for (int i = 0; i < 12; i++) {
            String m = ym.toString();
            Map<String, BigDecimal> cats = byMonth.getOrDefault(m, new LinkedHashMap<>());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", m);
            BigDecimal total = BigDecimal.ZERO;
            for (String c : CATEGORIES) {
                BigDecimal v = cats.getOrDefault(c, BigDecimal.ZERO);
                row.put(c, v);
                total = total.add(v);
            }
            row.put("total", total);
            trend.add(row);
            if (i == 10) {
                prevTotal = total;
            } else if (i == 11) {
                currTotal = total;
            }
            ym = ym.plusMonths(1);
        }
        out.put("trend", trend);

        // ---- 环比突变（FR-6-3 异常②）：>50% 或上月 0 当月 >0 ----
        Map<String, Object> spike = new LinkedHashMap<>();
        if (prevTotal != null && currTotal != null && currTotal.signum() > 0) {
            if (prevTotal.signum() == 0) {
                spike.put("spike", true);
                spike.put("momRatio", null);
                spike.put("spikeAlert", "当期成本 " + currTotal + " 元，上期为 0（新增成本），触发专项分析");
            } else {
                BigDecimal diff = currTotal.subtract(prevTotal)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(prevTotal, 2, RoundingMode.HALF_UP);
                boolean hit = diff.compareTo(new BigDecimal("50")) > 0;
                spike.put("spike", hit);
                spike.put("momRatio", diff);
                if (hit) {
                    spike.put("spikeAlert", "环比增长 " + diff + "% 超 50%，触发专项分析（FR-4.12-6-3）");
                }
            }
        }
        out.put("spike", spike);

        // ---- 四维排名（3.2）：产品/供应商/工序/客户，已确认口径，空值归「未归集」 ----
        Map<String, BigDecimal> byItem = new LinkedHashMap<>();
        Map<String, BigDecimal> byProcess = new LinkedHashMap<>();
        Map<String, BigDecimal> byCustomer = new LinkedHashMap<>();
        for (CopqEntry e : all) {
            if (!"CONFIRMED".equals(e.getStatus())) {
                continue;
            }
            mergeKey(byItem, e.getItemCode(), e.getAmount());
            mergeKey(byProcess, e.getProcessCode(), e.getAmount());
            mergeKey(byCustomer, e.getCustomerName(), e.getAmount());
        }
        Map<String, Object> rankings = new LinkedHashMap<>();
        rankings.put("ITEM", ranked(byItem));
        Map<String, BigDecimal> supForRank = new LinkedHashMap<>(bySupplier);
        supForRank.merge("未归因", unattributed, BigDecimal::add);
        rankings.put("SUPPLIER", ranked(supForRank));
        rankings.put("PROCESS", ranked(byProcess));
        rankings.put("CUSTOMER", ranked(byCustomer));
        out.put("rankings", rankings);

        // ---- 目标达成（3.2）：系统参数空 = 不参与考核 ----
        Map<String, Object> targets = new LinkedHashMap<>();
        boolean anyTarget = false;
        for (String c : CATEGORIES) {
            BigDecimal t = sysParamService.getAmount("COPQ_TARGET_" + c, null);
            if (t != null) {
                anyTarget = true;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("target", t);
                row.put("actual", confirmed.getOrDefault(c, BigDecimal.ZERO));
                row.put("gap", row.get("actual") instanceof BigDecimal a
                        ? a.subtract(t) : BigDecimal.ZERO.subtract(t));
                row.put("achieved", ((BigDecimal) row.get("actual")).compareTo(t) <= 0);
                targets.put(c, row);
            }
        }
        BigDecimal ratioTarget = sysParamService.getAmount("COPQ_TARGET_EXTERNAL_RATIO", null);
        if (ratioTarget != null) {
            anyTarget = true;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("target", ratioTarget);
            row.put("actual", ratio);
            row.put("gap", ratio.subtract(ratioTarget));
            row.put("achieved", ratio.compareTo(ratioTarget) <= 0);
            targets.put("EXTERNAL_RATIO", row);
        }
        out.put("targets", anyTarget ? targets : null);

        // ---- 改善建议（3.3）：三条规则式 ----
        List<String> suggestions = new ArrayList<>();
        if (alert) {
            suggestions.add("外部失败成本占比 " + ratio + "% 超 30%：建议启动供应商专项改善（SCAR）并纳入月度质量会议题（BR-4.12-41）");
        }
        if (Boolean.TRUE.equals(spike.get("spike"))) {
            suggestions.add(String.valueOf(spike.get("spikeAlert")));
        }
        if (confirmedTotal.signum() > 0) {
            String topCat = null;
            BigDecimal topVal = BigDecimal.ZERO;
            for (String c : CATEGORIES) {
                BigDecimal v = confirmed.getOrDefault(c, BigDecimal.ZERO);
                if (v.compareTo(topVal) > 0) {
                    topVal = v;
                    topCat = c;
                }
            }
            BigDecimal topShare = topVal.multiply(BigDecimal.valueOf(100))
                    .divide(confirmedTotal, 2, RoundingMode.HALF_UP);
            if ("INTERNAL_FAILURE".equals(topCat) && topShare.compareTo(new BigDecimal("50")) > 0) {
                suggestions.add("内部失败成本占比 " + topShare + "% 最高：建议强化前端防错与来料管控");
            }
        }
        out.put("suggestions", suggestions);
        out.put("suggestion", alert ? suggestions.get(0) : null); // 兼容旧键

        // ---- 数据缺失项（FR-6-3 异常① 轻量）：当月未确认类别与金额 ----
        List<Map<String, Object>> missing = new ArrayList<>();
        draft.forEach((c, v) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", c);
            row.put("amount", v);
            missing.add(row);
        });
        out.put("missing", missing);
        return out;
    }

    /** 键空归「未归集」的分组累加 */
    private void mergeKey(Map<String, BigDecimal> map, String key, BigDecimal amount) {
        String k = hasText(key) ? key : "未归集";
        map.merge(k, nvl(amount), BigDecimal::add);
    }

    /** 降序排名（含占比），最多 10 行 */
    private List<Map<String, Object>> ranked(Map<String, BigDecimal> src) {
        BigDecimal total = src.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Map<String, Object>> list = new ArrayList<>();
        src.forEach((k, v) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", k);
            row.put("amount", v);
            row.put("ratio", total.signum() > 0
                    ? v.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);
            list.add(row);
        });
        list.sort((a, b) -> ((BigDecimal) b.get("amount")).compareTo((BigDecimal) a.get("amount")));
        return list.size() > 10 ? new ArrayList<>(list.subList(0, 10)) : list;
    }

    @Override
    public List<CopqEntry> bySource(String sourceType, String sourceId) {
        return entryDao.selectList(new LambdaQueryWrapper<CopqEntry>()
                .eq(CopqEntry::getSourceType, sourceType)
                .eq(CopqEntry::getSourceId, sourceId));
    }

    // ================= 内部 =================

    private Map<String, Object> toRow(CopqEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("entryNo", e.getEntryNo());
        m.put("category", e.getCategory());
        m.put("amount", e.getAmount());
        m.put("sourceType", e.getSourceType());
        m.put("sourceNo", e.getSourceNo());
        m.put("ncrId", e.getNcrId());
        m.put("supplierName", e.getSupplierName());
        m.put("processCode", e.getProcessCode());
        m.put("customerName", e.getCustomerName());
        m.put("unattributedFlag", e.getUnattributedFlag());
        m.put("occurDate", e.getOccurDate());
        m.put("status", e.getStatus());
        m.put("anomalyFlag", e.getAnomalyFlag());
        m.put("remark", e.getRemark());
        m.put("createDate", e.getCreateDate());
        return m;
    }

    private CopqEntry require(String id) {
        CopqEntry e = entryDao.selectById(id);
        if (e == null) {
            throw new ServiceException(404, "成本归集单不存在：" + id);
        }
        return e;
    }

    private String nextEntryNo() {
        String prefix = "CO" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = entryDao.selectMaxSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    private void requireRole(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> userRoles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> userRoles.add(a.getAuthority()));
        if (userRoles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
            return;
        }
        for (String want : allowed) {
            for (String r : userRoles) {
                if (r.equalsIgnoreCase(want)) {
                    return;
                }
            }
        }
        throw new ServiceException(403, "当前角色无权" + action);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
