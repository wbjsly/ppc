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
    /** 实时余额（本位币 CNY） */
    private BigDecimal balance;
    /** 外币余额（add-fx-revaluation design D2 三列并存；CNY 账户恒等于 BALANCE，146 加列） */
    private BigDecimal fcBalance;
    /** 记账汇率（外币账户开户日中间价，CNY=1；146 加列） */
    private BigDecimal bookingRate;
    /** ACTIVE / DISABLED */
    private String status;
    private String remark;
}
