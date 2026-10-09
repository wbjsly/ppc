package com.erp.service.impl.proc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.proc.ProcBudgetDao;
import com.erp.entity.proc.ProcBudget;
import com.erp.service.proc.ProcBudgetService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 年度采购预算（design D7，spec purchase-budget）。
 * 阈值参数 PURCHASE_BUDGET_TRIGGER（默认 110%）来自 application.yml（参数化禁硬编码）。
 */
@Slf4j
@Service
public class ProcBudgetServiceImpl implements ProcBudgetService {

    /** 预算升级阈值（BR-4.2-17 / C-4.2-03，默认 110%） */
    @Value("${app.proc.purchase-budget-trigger:1.10}")
    private BigDecimal purchaseBudgetTrigger;

    private final ProcBudgetDao budgetDao;

    public ProcBudgetServiceImpl(ProcBudgetDao budgetDao) {
        this.budgetDao = budgetDao;
    }

    @Override
    public List<Map<String, Object>> list(Integer year, String keyword) {
        int y = year == null ? java.time.LocalDate.now().getYear() : year;
        LambdaQueryWrapper<ProcBudget> qw = new LambdaQueryWrapper<ProcBudget>()
                .eq(ProcBudget::getBudgetYear, y)
                .and(keyword != null && !keyword.trim().isEmpty(), w -> w
                        .like(ProcBudget::getCategoryCode, keyword.trim())
                        .or().like(ProcBudget::getCategoryName, keyword.trim()))
                .orderByAsc(ProcBudget::getCategoryCode);
        Map<String, BigDecimal> used = usageMap(y);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProcBudget b : budgetDao.selectList(qw)) {
            out.add(toRow(b, used.get(b.getCategoryCode() == null ? "" : b.getCategoryCode())));
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> usage(Integer yearArg) {
        int year = yearArg == null ? java.time.LocalDate.now().getYear() : yearArg;
        Map<String, BigDecimal> used = usageMap(year);
        List<Map<String, Object>> out = new ArrayList<>();
        // 有预算的行
        for (ProcBudget b : budgetDao.selectList(new LambdaQueryWrapper<ProcBudget>()
                .eq(ProcBudget::getBudgetYear, year)
                .orderByAsc(ProcBudget::getCategoryCode))) {
            out.add(toRow(b, used.get(b.getCategoryCode())));
            used.remove(b.getCategoryCode());
        }
        // 有累计但未设预算（NO_BUDGET，spec 场景「未设预算放行」的展示依据）
        for (Map.Entry<String, BigDecimal> e : used.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("categoryCode", e.getKey());
            row.put("categoryName", null);
            row.put("budgetYear", year);
            row.put("budgetAmt", null);
            row.put("usedAmt", e.getValue());
            row.put("remainAmt", null);
            row.put("triggerAmt", null);
            row.put("budgetFlag", "NO_BUDGET");
            out.add(row);
        }
        return out;
    }

    @Transactional
    @Override
    public Map<String, Object> save(Map<String, Object> payload) {
        Object idObj = payload.get("id");
        String id = idObj == null ? null : String.valueOf(idObj).trim();
        Integer year = toInt(payload.get("budgetYear"));
        String categoryCode = str(payload.get("categoryCode"));
        if (year == null || year < 2000 || year > 2100) {
            throw new ServiceException(422, "预算年度必填（2000-2100）");
        }
        if (categoryCode == null || categoryCode.trim().isEmpty()) {
            throw new ServiceException(422, "品类科目必填");
        }
        categoryCode = categoryCode.trim();
        BigDecimal amt = toDecimal(payload.get("budgetAmt"));
        if (amt == null || amt.signum() < 0) {
            throw new ServiceException(422, "预算金额须为非负数");
        }
        String reason = str(payload.get("changeReason"));

        // 唯一：同年度同科目
        ProcBudget dup = budgetDao.selectOne(new LambdaQueryWrapper<ProcBudget>()
                .eq(ProcBudget::getBudgetYear, year)
                .eq(ProcBudget::getCategoryCode, categoryCode)
                .last("LIMIT 1"));
        if (dup != null && !dup.getId().equals(id)) {
            throw new ServiceException(422, year + " 年度科目 " + categoryCode + " 预算已存在，请走修改");
        }

        if (id != null && !id.isEmpty() && !"null".equals(id)) {
            ProcBudget b = budgetDao.selectById(id);
            if (b == null) {
                throw new ServiceException(404, "预算记录不存在");
            }
            if (reason == null || reason.trim().length() < 2) {
                throw new ServiceException(422, "修改预算须填变更原因（不少于 2 字）");
            }
            BigDecimal before = b.getBudgetAmt();
            b.setBudgetAmt(amt);
            b.setCategoryName(str(payload.get("categoryName")));
            b.setChangeReason(reason.trim());
            b.setUpdateBy(SecurityUtils.getCurrentUserId());
            budgetDao.updateById(b);
            log.info("BUDGET {}-{} {} -> {} by {}", b.getBudgetYear(), b.getCategoryCode(),
                    before, amt, b.getUpdateBy());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("budget", b);
            r.put("before", before);
            return r;
        }

        ProcBudget b = new ProcBudget();
        b.setBudgetYear(year);
        b.setCategoryCode(categoryCode);
        b.setCategoryName(str(payload.get("categoryName")));
        b.setBudgetAmt(amt);
        b.setChangeReason(reason == null ? "新建" : reason.trim());
        b.setCreateBy(SecurityUtils.getCurrentUserId());
        budgetDao.insert(b);
        log.info("BUDGET {}-{} amount={} created by {}", year, categoryCode, amt, b.getCreateBy());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("budget", b);
        return r;
    }

    @Override
    public BigDecimal usedFor(String categoryCode, int year) {
        BigDecimal v = budgetDao.usedForCategory(year, categoryCode);
        return v == null ? BigDecimal.ZERO : v;
    }

    @Override
    public Map<String, Object> budgetOf(String categoryCode, int year) {
        ProcBudget b = budgetDao.selectOne(new LambdaQueryWrapper<ProcBudget>()
                .eq(ProcBudget::getBudgetYear, year)
                .eq(ProcBudget::getCategoryCode, categoryCode)
                .last("LIMIT 1"));
        if (b == null) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("budgetAmt", b.getBudgetAmt());
        row.put("usedAmt", usedFor(categoryCode, year));
        row.put("triggerAmt", b.getBudgetAmt().multiply(purchaseBudgetTrigger)
                .setScale(2, RoundingMode.HALF_UP));
        row.put("trigger", purchaseBudgetTrigger);
        return row;
    }

    // ---------- 私有 ----------

    private Map<String, BigDecimal> usageMap(int year) {
        Map<String, BigDecimal> used = new LinkedHashMap<>();
        for (Map<String, Object> r : budgetDao.usageByCategory(year)) {
            Object c = r.get("categoryCode");
            Object v = r.get("usedAmt");
            if (c != null) {
                used.put(String.valueOf(c), v instanceof BigDecimal ? (BigDecimal) v : new BigDecimal(String.valueOf(v)));
            }
        }
        return used;
    }

    private Map<String, Object> toRow(ProcBudget b, BigDecimal used) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", b.getId());
        row.put("budgetYear", b.getBudgetYear());
        row.put("categoryCode", b.getCategoryCode());
        row.put("categoryName", b.getCategoryName());
        row.put("budgetAmt", b.getBudgetAmt());
        BigDecimal u = used == null ? BigDecimal.ZERO : used;
        row.put("usedAmt", u);
        row.put("remainAmt", b.getBudgetAmt().subtract(u));
        row.put("triggerAmt", b.getBudgetAmt().multiply(purchaseBudgetTrigger)
                .setScale(2, RoundingMode.HALF_UP));
        row.put("budgetFlag", "OK");
        row.put("changeReason", b.getChangeReason());
        row.put("updateBy", b.getUpdateBy());
        row.put("updateDate", b.getUpdateDate());
        return row;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private Integer toInt(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal toDecimal(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
