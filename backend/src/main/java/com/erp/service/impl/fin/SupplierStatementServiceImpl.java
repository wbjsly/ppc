package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.fin.FinAccrualDao;
import com.erp.dao.fin.FinSupplierStatementDao;
import com.erp.entity.fin.FinAccrual;
import com.erp.entity.fin.FinSupplierStatement;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.fin.SupplierStatementService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * 供应商对账单与付款冻结实现（spec supplier-statement-reconciliation，C-4.2-15，design D2/D3）。
 * 冻结判定 = 存在 EXCEPTION 单（派生，不加冗余列）；双签走审批底座两节点，通过由回调置 CLOSED。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierStatementServiceImpl implements SupplierStatementService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 对账容差（TOLERANCE_DEFAULT，C-4.2-15，与收货容差同一参数源） */
    @Value("${app.proc.receipt-tolerance:0.005}")
    private BigDecimal tolerance;

    private final FinSupplierStatementDao stmtDao;
    private final FinAccrualDao accrualDao;
    private final ApprovalEngine approvalEngine;

    @Override
    @Transactional
    public FinSupplierStatement register(Map<String, Object> payload) {
        String supplierId = str(payload.get("supplierId"));
        if (!hasText(supplierId)) {
            throw new ServiceException(422, "供应商必填");
        }
        BigDecimal stmtAmount = dec(payload.get("stmtAmount"));
        if (stmtAmount == null) {
            throw new ServiceException(422, "对账金额必填");
        }
        LocalDate stmtDate = parseDate(str(payload.get("stmtDate")));

        // 比对基准 = 该供应商 OPEN 暂估余额合计（spec supplier-statement-reconciliation）
        BigDecimal base = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (FinAccrual a : accrualDao.selectList(new LambdaQueryWrapper<FinAccrual>()
                .eq(FinAccrual::getSupplierId, supplierId)
                .eq(FinAccrual::getStatus, FinAccrual.ST_OPEN))) {
            base = base.add(nvl(a.getAmount()).subtract(nvl(a.getOffsettedAmount())));
        }
        BigDecimal diff = stmtAmount.setScale(2, RoundingMode.HALF_UP).subtract(base);
        BigDecimal rate;
        if (base.signum() > 0) {
            rate = diff.abs().divide(base, 6, RoundingMode.HALF_UP);
        } else {
            rate = stmtAmount.signum() > 0 ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        boolean frozen = rate.compareTo(tolerance) > 0;

        FinSupplierStatement s = new FinSupplierStatement();
        s.setStmtNo(nextStmtNo());
        s.setSupplierId(supplierId);
        s.setSupplierName(str(payload.get("supplierName")));
        s.setStmtDate(stmtDate);
        s.setStmtAmount(stmtAmount.setScale(2, RoundingMode.HALF_UP));
        s.setBaseAmount(base);
        s.setDiffAmount(diff);
        s.setDiffRate(rate);
        s.setStatus(frozen ? FinSupplierStatement.ST_EXCEPTION : FinSupplierStatement.ST_ACCEPTED);
        s.setRemark(str(payload.get("remark")));
        s.setCreateBy(SecurityUtils.getCurrentUserId());
        stmtDao.insert(s);
        if (frozen) {
            log.info("对账单 {} 差异 {}（差异率 {} > 容差 {}）→ 冻结供应商 {} 付款（C-4.2-15）",
                    s.getStmtNo(), diff, rate, tolerance, supplierId);
        }
        return s;
    }

    @Override
    public Page<Map<String, Object>> page(long current, long size, String supplierId, String status,
                                          String dateFrom, String dateTo) {
        LambdaQueryWrapper<FinSupplierStatement> qw = new LambdaQueryWrapper<FinSupplierStatement>()
                .eq(hasText(supplierId), FinSupplierStatement::getSupplierId, supplierId)
                .eq(hasText(status), FinSupplierStatement::getStatus, status)
                .ge(hasText(dateFrom), FinSupplierStatement::getStmtDate, dateFrom)
                .le(hasText(dateTo), FinSupplierStatement::getStmtDate, dateTo)
                .orderByDesc(FinSupplierStatement::getCreateDate);
        Page<FinSupplierStatement> raw = stmtDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FinSupplierStatement s : raw.getRecords()) {
            rows.add(row(s));
        }
        out.setRecords(rows);
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        return row(require(id));
    }

    @Override
    @Transactional
    public Map<String, Object> confirm(String id) {
        requireRole("发起对账双签", "ROLE_PM", "ROLE_FINANCE_MGR");
        FinSupplierStatement s = require(id);
        if (!FinSupplierStatement.ST_EXCEPTION.equals(s.getStatus())) {
            throw new ServiceException(422, "仅差异对账单需双签，当前状态 " + s.getStatus());
        }
        if (approvalEngine.findByBiz("StatementReconcile", s.getId()) != null) {
            throw new ServiceException(422, "该对账单已发起双签确认，请勿重复发起");
        }
        approvalEngine.submit("StatementReconcile", s.getId(),
                "对账差异双签：" + s.getStmtNo() + " 差异 " + s.getDiffAmount().toPlainString(),
                "ROLE_FINANCE_MGR",
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_PM", "采购员确认")),
                        List.of(ApprovalNodeSpec.sign("ROLE_FINANCE_MGR", "财务确认"))));
        log.info("对账单 {} 发起双签（PM → 财务），冻结中", s.getStmtNo());
        return detail(id);
    }

    @Override
    public void assertNotFrozen(String supplierId) {
        if (!hasText(supplierId)) {
            return;
        }
        if (stmtDao.countFrozen(supplierId) <= 0) {
            return;
        }
        FinSupplierStatement s = stmtDao.selectOne(new LambdaQueryWrapper<FinSupplierStatement>()
                .eq(FinSupplierStatement::getSupplierId, supplierId)
                .eq(FinSupplierStatement::getStatus, FinSupplierStatement.ST_EXCEPTION)
                .orderByDesc(FinSupplierStatement::getCreateDate)
                .last("LIMIT 1"));
        throw new ServiceException(422, "对账差异冻结付款（C-4.2-15）：差异对账单 "
                + (s == null ? "" : s.getStmtNo()) + " 待采购员与财务双签确认");
    }

    // ------------------------------------------------------------------

    private Map<String, Object> row(FinSupplierStatement s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("stmtNo", s.getStmtNo());
        m.put("supplierId", s.getSupplierId());
        m.put("supplierName", s.getSupplierName());
        m.put("stmtDate", s.getStmtDate());
        m.put("stmtAmount", s.getStmtAmount());
        m.put("baseAmount", s.getBaseAmount());
        m.put("diffAmount", s.getDiffAmount());
        m.put("diffRate", s.getDiffRate());
        m.put("tolerance", tolerance);
        m.put("status", s.getStatus());
        m.put("frozen", FinSupplierStatement.ST_EXCEPTION.equals(s.getStatus()));
        m.put("pmConfirmBy", s.getPmConfirmBy());
        m.put("pmConfirmAt", s.getPmConfirmAt());
        m.put("pmOpinion", s.getPmOpinion());
        m.put("finConfirmBy", s.getFinConfirmBy());
        m.put("finConfirmAt", s.getFinConfirmAt());
        m.put("finOpinion", s.getFinOpinion());
        m.put("remark", s.getRemark());
        m.put("matchResult", s.getMatchResult());
        m.put("matchBy", s.getMatchBy());
        m.put("matchAt", s.getMatchAt());
        m.put("createDate", s.getCreateDate());
        return m;
    }

    @Override
    @Transactional
    public Map<String, Object> match(String id, Map<String, Object> payload) {
        FinSupplierStatement stmt = require(id);
        String prevBy = stmt.getMatchBy();
        LocalDateTime prevAt = stmt.getMatchAt();

        // ERP 侧：该供应商 OPEN 暂估构成（来源 = 入库过账 IV / 寄售转自有 VT，即入库与领用明细聚合）
        List<FinAccrual> openAccruals = accrualDao.selectList(new LambdaQueryWrapper<FinAccrual>()
                .eq(FinAccrual::getSupplierId, stmt.getSupplierId())
                .eq(FinAccrual::getStatus, "OPEN")
                .orderByAsc(FinAccrual::getAccrualNo));
        List<Map<String, Object>> erpRows = new ArrayList<>();
        BigDecimal erpTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (FinAccrual a : openAccruals) {
            BigDecimal open = nvl(a.getAmount()).subtract(nvl(a.getOffsettedAmount()))
                    .setScale(2, RoundingMode.HALF_UP);
            if (open.signum() <= 0) {
                continue;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("accrualNo", a.getAccrualNo());
            r.put("sourceType", a.getSourceType());
            r.put("postingDocNo", a.getPostingDocNo());
            r.put("grNo", a.getGrNo());
            r.put("poNo", a.getPoNo());
            r.put("amount", open);
            erpRows.add(r);
            erpTotal = erpTotal.add(open);
        }

        // 供应商侧：明细（可选）缺省以对账单合计作单行
        List<Map<String, Object>> supRows = new ArrayList<>();
        Object linesObj = payload == null ? null : payload.get("lines");
        if (linesObj instanceof List && !((List<?>) linesObj).isEmpty()) {
            for (Object o : (List<?>) linesObj) {
                if (!(o instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> l = (Map<String, Object>) o;
                BigDecimal amt = dec(l.get("amount"));
                if (amt == null) {
                    continue;
                }
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("desc", l.get("desc") == null ? l.get("itemCode") : l.get("desc"));
                r.put("amount", amt.setScale(2, RoundingMode.HALF_UP));
                supRows.add(r);
            }
        }
        if (supRows.isEmpty()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("desc", "对账单合计");
            r.put("amount", nvl(stmt.getStmtAmount()).setScale(2, RoundingMode.HALF_UP));
            supRows.add(r);
        }
        BigDecimal supTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (Map<String, Object> r : supRows) {
            supTotal = supTotal.add((BigDecimal) r.get("amount"));
        }

        // 逐笔贪心匹配（金额相等配对）→ 差异清单标红
        List<Map<String, Object>> matched = new ArrayList<>();
        List<Map<String, Object>> supplierOnly = new ArrayList<>();
        boolean[] used = new boolean[erpRows.size()];
        for (Map<String, Object> sr : supRows) {
            BigDecimal amt = (BigDecimal) sr.get("amount");
            int hit = -1;
            for (int i = 0; i < erpRows.size(); i++) {
                if (!used[i] && amt.compareTo((BigDecimal) erpRows.get(i).get("amount")) == 0) {
                    hit = i;
                    break;
                }
            }
            if (hit >= 0) {
                used[hit] = true;
                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("supplierDesc", sr.get("desc"));
                pair.put("supplierAmount", amt);
                pair.put("accrualNo", erpRows.get(hit).get("accrualNo"));
                pair.put("postingDocNo", erpRows.get(hit).get("postingDocNo"));
                matched.add(pair);
            } else {
                Map<String, Object> u = new LinkedHashMap<>(sr);
                u.put("flag", "SUPPLIER_ONLY");
                u.put("red", true);
                supplierOnly.add(u);
            }
        }
        List<Map<String, Object>> erpOnly = new ArrayList<>();
        for (int i = 0; i < erpRows.size(); i++) {
            if (!used[i]) {
                Map<String, Object> u = new LinkedHashMap<>(erpRows.get(i));
                u.put("flag", "ERP_ONLY");
                u.put("red", true);
                erpOnly.add(u);
            }
        }

        BigDecimal diff = supTotal.subtract(erpTotal).setScale(2, RoundingMode.HALF_UP);
        BigDecimal rate;
        if (erpTotal.signum() == 0) {
            rate = supTotal.signum() == 0 ? BigDecimal.ZERO : BigDecimal.ONE;
        } else {
            rate = diff.abs().divide(erpTotal, 6, RoundingMode.HALF_UP);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("erpTotal", erpTotal);
        result.put("supplierTotal", supTotal);
        result.put("diffAmount", diff);
        result.put("diffRate", rate);
        result.put("matched", matched);
        result.put("supplierOnly", supplierOnly);
        result.put("erpOnly", erpOnly);
        result.put("redCount", supplierOnly.size() + erpOnly.size());
        result.put("matchBy", SecurityUtils.getCurrentUserId());
        result.put("matchAt", LocalDateTime.now());
        result.put("prevMatchBy", prevBy);
        result.put("prevMatchAt", prevAt);

        String json;
        try {
            json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .findAndRegisterModules()
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .writeValueAsString(result);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException(500, "匹配结果序列化失败");
        }
        stmt.setMatchResult(json);
        stmt.setMatchBy(SecurityUtils.getCurrentUserId());
        stmt.setMatchAt(LocalDateTime.now());
        if (stmtDao.updateById(stmt) == 0) {
            throw new ServiceException(409, "对账单匹配结果更新冲突");
        }
        log.info("对账单 {} 系统匹配：ERP {} / 供应商 {} / 差异 {} 标红 {} by {}",
                stmt.getStmtNo(), erpTotal, supTotal, diff, result.get("redCount"),
                stmt.getMatchBy());
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("id", stmt.getId());
        out.put("stmtNo", stmt.getStmtNo());
        return out;
    }

    private FinSupplierStatement require(String id) {
        FinSupplierStatement s = stmtDao.selectById(id);
        if (s == null) {
            throw new ServiceException(404, "对账单不存在");
        }
        return s;
    }

    private String nextStmtNo() {
        String prefix = "ST" + LocalDate.now().format(DAY_FMT);
        Integer max = stmtDao.selectMaxSeq(prefix);
        return prefix + String.format("%03d", (max == null ? 0 : max) + 1);
    }

    private void requireRole(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> userRoles = new ArrayList<>();
        auth.getAuthorities().forEach(r -> userRoles.add(r.getAuthority()));
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

    private static LocalDate parseDate(String s) {
        if (!hasText(s)) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(s);
        } catch (RuntimeException e) {
            throw new ServiceException(422, "对账日期格式须为 yyyy-MM-dd");
        }
    }

    private static BigDecimal dec(Object o) {
        if (o == null || "".equals(String.valueOf(o))) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            throw new ServiceException(422, "金额格式错误：" + o);
        }
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
