package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 假期类型字典（8 类种子，规则参数化）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_leave_type")
public class HrLeaveType extends BaseEntity {
    public static final String CODE_COMP_TIME = "COMP_TIME";
    public static final String CODE_ANNUAL = "ANNUAL";
    public static final String CODE_PERSONAL = "PERSONAL";
    public static final String CODE_SICK = "SICK";
    public static final String CODE_MARRIAGE = "MARRIAGE";
    public static final String CODE_MATERNITY = "MATERNITY";
    public static final String CODE_PATERNITY = "PATERNITY";
    public static final String CODE_BEREAVEMENT = "BEREAVEMENT";

    private String typeCode;
    private String name;
    /** 是否消耗余额池（调休/年假=Y） */
    private String needBalance;
    /** 日工资扣减系数（事假 1.0/病假 0.6/带薪假 0） */
    private BigDecimal dailyDeductionRate;
    /** 法定天数上限（婚/产/陪产/丧假） */
    private BigDecimal statutoryDays;
    private String paidFlag;
    private Integer sortNo;
    private String status;
}
