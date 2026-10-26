package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** PayrollRun 明细快照（生成时落库冻结，全部分项可追溯）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_payroll_run_item")
public class HrPayrollRunItem extends BaseEntity {
    public static final String ST_NORMAL = "NORMAL";
    public static final String ST_NEGATIVE = "NEGATIVE";
    public static final String ST_VOLATILE = "VOLATILE";
    public static final String ST_HOLD = "HOLD";

    private String runId;
    private String period;
    private String empId;
    private String empNo;
    private String empName;
    private String deptId;
    private String costCenter;
    /** 基本工资（出勤折算后） */
    private BigDecimal baseSalary;
    private BigDecimal perfAmount;
    private String perfGrade;
    private BigDecimal perfCoef;
    /** 如 无绩效结果 */
    private String perfNote;
    /** 补贴合计（补扣项正向） */
    private BigDecimal allowance;
    /** 加班工资（取固化金额） */
    private BigDecimal overtimeWage;
    private BigDecimal gross;
    private BigDecimal socialEe;
    private BigDecimal providentEe;
    /** 社保备注（如「已减员」，social-insurance-ops 衔接标注） */
    private String socialNote;
    private BigDecimal iit;
    /** 其他扣款（罚款+请假日工资扣减） */
    private BigDecimal otherDeduct;
    /** 请假日工资扣减（单列供汇总） */
    private BigDecimal leaveDeduct;
    private BigDecimal net;
    private String negFlag;
    private String volatileFlag;
    private String holdFlag;
    private String status;
}
