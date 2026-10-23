package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 坏账计提台账（add-ar-management design D3）。
 * PERIOD+CUSTOMER_ID 唯一；BUCKET_BASE 存六段基数快照 JSON（参数调段数不回溯已落库台账）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_fin_ar_provision")
public class ArProvision extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_CONFIRMED = "CONFIRMED";

    private String provisionNo;
    /** 会计期间 YYYYMM */
    private String period;
    private String customerId;
    private String customerCode;
    private String customerName;
    /** 六段基数快照 [{bucket,base,rate,accrual}...] */
    private String bucketBase;
    /** 目标余额 = Σ段计提额 */
    private BigDecimal targetBalance;
    /** 已提余额（冲回前） */
    private BigDecimal bookedBalance;
    /** 本期计提额（可负=冲减） */
    private BigDecimal periodAccrual;
    /** 1=上期计提已冲回 */
    private String reversedFlag;
    /** 冲回凭证锚 */
    private String reversedVoucherId;
    /** 本期计提凭证锚 */
    private String voucherId;
    /** DRAFT / CONFIRMED */
    private String status;
    private String remark;
}
