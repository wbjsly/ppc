package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 信用检查记录（BR-4.3-13 四因子 + 账龄/及时率快照，判定留痕）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_credit_check")
public class CreditCheck extends BaseEntity {

    public static final String R_PASS = "PASS";
    public static final String R_FROZEN = "FROZEN";
    public static final String R_NEED_APPROVAL = "NEED_APPROVAL";

    private String customerId;
    private String customerCode;
    private String customerName;
    private String soId;
    private String soNo;
    private BigDecimal orderAmount;
    /** 含有效临时额度 */
    private BigDecimal creditLimitTotal;
    private BigDecimal arBalance;
    /** 未清 SO 预占 */
    private BigDecimal soReserved;
    /** 寄售专项占用 */
    private BigDecimal consignOccupied;
    /** 可用额度 = 额度 − AR − 预占 − 本次 − 寄售 */
    private BigDecimal available;
    /** 超期 90 天以上应收 */
    private BigDecimal arOver90;
    private BigDecimal over90Ratio;
    /** 近 12 个月付款及时率 */
    private BigDecimal payOnTimeRate;
    private String result;
    private String reason;
    /** 账龄分析报告 JSON（L2 派发信用管理员） */
    private String agingReport;
    private String checkBy;
    private LocalDateTime checkAt;
}
