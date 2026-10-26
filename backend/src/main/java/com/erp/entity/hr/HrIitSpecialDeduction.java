package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 专项附加扣除（员工自助端维护，核算期取值快照；税务系统同步留桩）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_iit_special_deduction")
public class HrIitSpecialDeduction extends BaseEntity {
    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_DISABLED = "DISABLED";

    private String empId;
    /** CHILD_EDUCATION/CONTINUE_EDU/HOUSING_LOAN/HOUSING_RENT/SUPPORT_ELDERLY/INFANT_CARE */
    private String deductType;
    /** 每月分摊金额 */
    private BigDecimal monthlyAmount;
    private String status;
}
