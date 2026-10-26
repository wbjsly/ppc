package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 工资凭证科目映射（COST_CENTER→费用科目；FIXED 键承载贷方固定科目）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_pay_account_mapping")
public class HrPayAccountMapping extends BaseEntity {
    public static final String TYPE_COST_CENTER = "COST_CENTER";
    public static final String TYPE_FIXED = "FIXED";

    public static final String KEY_SALARY_PAYABLE = "SALARY_PAYABLE";
    public static final String KEY_SOCIAL_PAYABLE = "SOCIAL_PAYABLE";
    public static final String KEY_IIT_PAYABLE = "IIT_PAYABLE";
    public static final String KEY_BANK = "BANK_ACCT";
    public static final String KEY_DEFAULT_EXPENSE = "DEFAULT_EXPENSE";

    private String mappingType;
    private String mappingKey;
    private String accountCode;
    private String remark;
}
