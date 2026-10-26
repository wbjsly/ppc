package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrIitCumulativeDao;
import com.erp.dao.hr.HrIitSpecialDeductionDao;
import com.erp.dao.hr.HrIitTaxTableDao;
import com.erp.entity.hr.HrIitCumulative;
import com.erp.entity.hr.HrIitSpecialDeduction;
import com.erp.entity.hr.HrIitTaxTable;
import com.erp.service.hr.HrIitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 个税实现（累计预扣读-算-写回；年终奖单独/并入二选一；专项附加自助端维护+快照）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrIitServiceImpl implements HrIitService {

    /** 每月减除费用 5000（个税法固定值） */
    private static final BigDecimal MONTHLY_THRESHOLD = new BigDecimal("5000");

    private final HrIitTaxTableDao taxTableDao;
    private final HrIitCumulativeDao cumulativeDao;
    private final HrIitSpecialDeductionDao deductionDao;

    @Override
    public List<HrIitTaxTable> taxTable(int year) {
        return taxTableDao.selectList(new LambdaQueryWrapper<HrIitTaxTable>()
                .eq(HrIitTaxTable::getYear, year)
                .eq(HrIitTaxTable::getDelFlag, "0")
                .orderByAsc(HrIitTaxTable::getLevelNo));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrIitSpecialDeduction saveSpecialDeduction(Map<String, Object> payload) {
        String empId = str(payload.get("empId"));
        String type = str(payload.get("deductType"));
        if (empId == null || empId.isBlank()) {
            throw new ServiceException(422, "员工必填");
        }
        if (type == null || type.isBlank()) {
            throw new ServiceException(422, "扣除类型必填");
        }
        BigDecimal amount = dec(payload.get("monthlyAmount"));
        if (amount == null || amount.signum() < 0) {
            throw new ServiceException(422, "每月分摊金额必填且不可为负");
        }
        HrIitSpecialDeduction d = deductionDao.selectOne(new LambdaQueryWrapper<HrIitSpecialDeduction>()
                .eq(HrIitSpecialDeduction::getEmpId, empId)
                .eq(HrIitSpecialDeduction::getDeductType, type)
                .eq(HrIitSpecialDeduction::getDelFlag, "0"));
        boolean isNew = d == null;
        if (isNew) {
            d = new HrIitSpecialDeduction();
            d.setId(uuid());
            d.setEmpId(empId);
            d.setDeductType(type);
            d.setCreateBy(currentUser());
            d.setCreateDate(LocalDateTime.now());
        } else {
            d.setUpdateBy(currentUser());
            d.setUpdateDate(LocalDateTime.now());
        }
        d.setMonthlyAmount(amount);
        d.setStatus(HrIitSpecialDeduction.ST_ACTIVE);
        if (isNew) {
            deductionDao.insert(d);
        } else {
            deductionDao.updateById(d);
        }
        return d;
    }

    @Override
    public List<HrIitSpecialDeduction> specialDeductions(String empId) {
        LambdaQueryWrapper<HrIitSpecialDeduction> qw = new LambdaQueryWrapper<HrIitSpecialDeduction>()
                .eq(HrIitSpecialDeduction::getDelFlag, "0")
                .orderByAsc(HrIitSpecialDeduction::getDeductType);
        if (empId != null && !empId.isBlank()) {
            qw.eq(HrIitSpecialDeduction::getEmpId, empId);
        }
        return deductionDao.selectList(qw);
    }

    @Override
    public BigDecimal monthlySpecialTotal(String empId) {
        return deductionDao.selectList(new LambdaQueryWrapper<HrIitSpecialDeduction>()
                        .eq(HrIitSpecialDeduction::getEmpId, empId)
                        .eq(HrIitSpecialDeduction::getStatus, HrIitSpecialDeduction.ST_ACTIVE)
                        .eq(HrIitSpecialDeduction::getDelFlag, "0"))
                .stream().map(HrIitSpecialDeduction::getMonthlyAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> calcMonthly(String empId, int year, int month,
                                           BigDecimal gross, BigDecimal socialPersonal,
                                           BigDecimal specialDeduction) {
        if (gross == null || gross.signum() < 0) {
            throw new ServiceException(422, "累计应发非法");
        }
        HrIitCumulative cum = requireOrCreate(empId, year);
        // 减除费用按月数累计（month 为本核算月）
        BigDecimal threshold = MONTHLY_THRESHOLD.multiply(BigDecimal.valueOf(month));
        BigDecimal cumTaxable = cum.getCumGross().add(gross)
                .subtract(cum.getCumDeduct()).subtract(nvl(socialPersonal))
                .subtract(cum.getCumSpecial()).subtract(nvl(specialDeduction))
                .subtract(threshold);
        BigDecimal tax;
        BigDecimal newCumTaxable = cumTaxable.max(BigDecimal.ZERO);
        if (newCumTaxable.signum() <= 0) {
            tax = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        } else {
            HrIitTaxTable bracket = matchBracket(year, newCumTaxable);
            BigDecimal cumTax = newCumTaxable.multiply(bracket.getRate())
                    .subtract(bracket.getQuickDeduct()).setScale(2, RoundingMode.HALF_UP);
            tax = cumTax.subtract(cum.getCumTaxPaid()).max(BigDecimal.ZERO);
        }
        // 写回台账（读-算-写回）
        cum.setCumGross(cum.getCumGross().add(gross));
        cum.setCumDeduct(cum.getCumDeduct().add(nvl(socialPersonal)));
        cum.setCumSpecial(cum.getCumSpecial().add(nvl(specialDeduction)));
        cum.setCumTaxPaid(cum.getCumTaxPaid().add(tax));
        cum.setUpdateBy(currentUser());
        cum.setUpdateDate(LocalDateTime.now());
        cumulativeDao.updateById(cum);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tax", tax);
        out.put("cumTaxable", newCumTaxable);
        out.put("cumTaxPaid", cum.getCumTaxPaid());
        return out;
    }

    @Override
    public Map<String, Object> bonusTax(String empId, BigDecimal bonus, String choice) {
        if (bonus == null || bonus.signum() <= 0) {
            throw new ServiceException(422, "年终奖金额必须大于 0");
        }
        String ch = choice == null ? "SINGLE" : choice.toUpperCase();
        BigDecimal tax;
        if ("SINGLE".equals(ch)) {
            // 单独计税：bonus÷12 换算月度 → ×12 匹配年度累计级距（税率同表），速算扣除数÷12
            BigDecimal monthly = bonus.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
            HrIitTaxTable bracket = matchBracket(LocalDate.now().getYear(), monthly.multiply(BigDecimal.valueOf(12)));
            BigDecimal quickMonthly = bracket.getQuickDeduct()
                    .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
            tax = bonus.multiply(bracket.getRate()).subtract(quickMonthly)
                    .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        } else if ("COMBINED".equals(ch)) {
            // 并入综合所得：由核算月累计预扣法自然吸收，此处返回 0 预估（实际随当月 calcMonthly 计算）
            tax = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        } else {
            throw new ServiceException(422, "choice 仅支持 SINGLE/COMBINED");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("empId", empId);
        out.put("bonus", bonus);
        out.put("choice", ch);
        out.put("tax", tax);
        out.put("afterTax", bonus.subtract(tax));
        return out;
    }

    @Override
    public Map<String, Object> cumulative(String empId, int year) {
        HrIitCumulative cum = cumulativeDao.selectOne(new LambdaQueryWrapper<HrIitCumulative>()
                .eq(HrIitCumulative::getEmpId, empId)
                .eq(HrIitCumulative::getYear, year)
                .eq(HrIitCumulative::getDelFlag, "0"));
        Map<String, Object> out = new LinkedHashMap<>();
        if (cum == null) {
            out.put("exists", false);
            return out;
        }
        out.put("exists", true);
        out.put("cumGross", cum.getCumGross());
        out.put("cumDeduct", cum.getCumDeduct());
        out.put("cumSpecial", cum.getCumSpecial());
        out.put("cumTaxPaid", cum.getCumTaxPaid());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void carryOver(String empId, int year, BigDecimal cumGross, BigDecimal cumDeduct,
                          BigDecimal cumSpecial, BigDecimal cumTaxPaid) {
        if (cumulativeDao.selectCount(new LambdaQueryWrapper<HrIitCumulative>()
                .eq(HrIitCumulative::getEmpId, empId)
                .eq(HrIitCumulative::getYear, year)
                .eq(HrIitCumulative::getDelFlag, "0")) > 0) {
            throw new ServiceException(422, "该员工本年累计已存在，勿重复结转");
        }
        HrIitCumulative cum = new HrIitCumulative();
        cum.setId(uuid());
        cum.setEmpId(empId);
        cum.setYear(year);
        cum.setCumGross(nvl(cumGross));
        cum.setCumDeduct(nvl(cumDeduct));
        cum.setCumSpecial(nvl(cumSpecial));
        cum.setCumTaxPaid(nvl(cumTaxPaid));
        cum.setCreateBy(currentUser());
        cum.setCreateDate(LocalDateTime.now());
        cumulativeDao.insert(cum);
    }

    // ================================================================
    // helpers
    // ================================================================

    private HrIitCumulative requireOrCreate(String empId, int year) {
        HrIitCumulative cum = cumulativeDao.selectOne(new LambdaQueryWrapper<HrIitCumulative>()
                .eq(HrIitCumulative::getEmpId, empId)
                .eq(HrIitCumulative::getYear, year)
                .eq(HrIitCumulative::getDelFlag, "0"));
        if (cum == null) {
            cum = new HrIitCumulative();
            cum.setId(uuid());
            cum.setEmpId(empId);
            cum.setYear(year);
            cum.setCumGross(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            cum.setCumDeduct(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            cum.setCumSpecial(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            cum.setCumTaxPaid(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            cum.setCreateBy(currentUser());
            cum.setCreateDate(LocalDateTime.now());
            cumulativeDao.insert(cum);
        }
        return cum;
    }

    /** 按累计应纳税所得额匹配税率级距。 */
    private HrIitTaxTable matchBracket(int year, BigDecimal taxable) {
        List<HrIitTaxTable> brackets = taxTable(year);
        if (brackets.isEmpty()) {
            throw new ServiceException(422, year + " 年税率表未配置（L1 阻断计税）");
        }
        for (HrIitTaxTable b : brackets) {
            boolean geLower = taxable.compareTo(b.getLowerBound()) >= 0;
            boolean ltUpper = b.getUpperBound() == null || taxable.compareTo(b.getUpperBound()) < 0;
            if (geLower && ltUpper) {
                return b;
            }
        }
        throw new ServiceException(422, "累计应纳税所得额超出税率表范围：" + taxable);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static BigDecimal dec(Object o) {
        if (o == null || String.valueOf(o).trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (Exception e) {
            throw new ServiceException(422, "数值格式非法：" + o);
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String currentUser() {
        try {
            return org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication().getName();
        } catch (Exception e) {
            return "system";
        }
    }
}
