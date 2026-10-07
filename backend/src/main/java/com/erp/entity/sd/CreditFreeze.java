package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 信用冻结单（D5 挂起态）：PREV_STATUS 记录前一稳定状态，
 * 解冻回该状态且不重跑审批、不释放预留（spec customer-credit-control）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_credit_freeze")
public class CreditFreeze extends BaseEntity {

    public static final String ST_FROZEN = "FROZEN";
    public static final String ST_UNFROZEN = "UNFROZEN";

    public static final String RS_LIMIT = "LIMIT";
    public static final String RS_AGING = "AGING";

    public static final String M_PREPAY = "PREPAY";
    public static final String M_SPECIAL = "SPECIAL";
    public static final String M_MANUAL = "MANUAL";

    private String soId;
    private String soNo;
    private String customerId;
    private String customerName;
    private String creditCheckId;
    /** 缺口金额 = −可用额度（预收目标） */
    private BigDecimal gapAmount;
    private String status;
    /** 冻结时 SO 的前一稳定状态 */
    private String prevStatus;
    private String freezeReason;
    private LocalDateTime frozenAt;
    private String unfreezeMethod;
    /** 《SO 解冻确认单》编号 */
    private String unfreezeNo;
    private String unfrozenBy;
    private LocalDateTime unfrozenAt;
    private String unfreezeRemark;
}
