package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** 银行账户（2.7.3，spec bank-account，design D1）：付款执行的余额来源，条件更新防透支（BR-4.6-21）。 */
@Getter
@Setter
@TableName("erp_fin_bank_account")
public class FinBankAccount extends BaseEntity {

    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_DISABLED = "DISABLED";

    private String accountName;
    /** 账号（唯一） */
    private String accountNo;
    private String currency;
    /** 实时余额 */
    private BigDecimal balance;
    /** ACTIVE / DISABLED */
    private String status;
    private String remark;
}
