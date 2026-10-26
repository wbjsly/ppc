package com.erp.service.hr;

import com.erp.entity.hr.HrIitSpecialDeduction;
import com.erp.entity.hr.HrIitTaxTable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 个税计算（payroll-calculation 10.5.2：累计预扣/专项附加/年终奖）。 */
public interface HrIitService {

    /** 税率表查询（YEAR 维度，官方七级）。 */
    List<HrIitTaxTable> taxTable(int year);

    /** 专项附加扣除维护（员工自助端 CRUD，upsert 按 empId+type）。 */
    HrIitSpecialDeduction saveSpecialDeduction(Map<String, Object> payload);

    /** 专项附加扣除清单（empId 过滤）。 */
    List<HrIitSpecialDeduction> specialDeductions(String empId);

    /** 当月专项附加扣除合计（核算期快照用）。 */
    BigDecimal monthlySpecialTotal(String empId);

    /**
     * 累计预扣法月度计算并写回台账（读-算-写回）：
     * 累计应纳税所得额 = 累计应发 - 累计专项扣除 - 累计专项附加 - 累计减除费用(5000×月数)
     * 本期税 = 累计所得×预扣率 - 速算扣除数 - 累计已预扣
     */
    Map<String, Object> calcMonthly(String empId, int year, int month,
                                    BigDecimal gross, BigDecimal socialPersonal, BigDecimal specialDeduction);

    /**
     * 年终奖计税（choice=SINGLE 单独计税 / COMBINED 并入综合）：
     * 单独： bonus÷12 适用月度税率 → bonus×rate - quickDeduct
     * 返回 {choice, tax, afterTax}，选择与税额留痕由调用方落单据。
     */
    Map<String, Object> bonusTax(String empId, BigDecimal bonus, String choice);

    /** 人×年累计台账查询。 */
    Map<String, Object> cumulative(String empId, int year);

    /** 年度期初结转（首月把上年累计写入，支持年末一次性结转）。 */
    void carryOver(String empId, int year, BigDecimal cumGross, BigDecimal cumDeduct,
                   BigDecimal cumSpecial, BigDecimal cumTaxPaid);
}
