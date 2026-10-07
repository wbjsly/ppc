package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预收款通知单与到账登记（FR-4.3-2-6/2-7，BR-4.3-17）。
 * 冻结后自动通知销售与客户；财务确认到账足额 → 自动解冻。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_prepayment_notice")
public class PrepaymentNotice extends BaseEntity {

    public static final String ST_PENDING = "PENDING";
    public static final String ST_NOTIFIED = "NOTIFIED";
    public static final String ST_REGISTERED = "REGISTERED";
    public static final String ST_SETTLED = "SETTLED";

    private String noticeNo;
    private String freezeId;
    private String soId;
    private String soNo;
    private String customerId;
    private String customerName;
    /** 预收目标金额 = 缺口 */
    private BigDecimal gapAmount;
    private LocalDateTime notifySalesAt;
    private LocalDateTime notifyCustomerAt;
    private BigDecimal receivedAmount;
    private LocalDateTime receivedAt;
    /** 财务确认人（仅 FINANCE_MGR/ADMIN） */
    private String confirmBy;
    private LocalDateTime confirmAt;
    private String status;
    /** 尚差金额 */
    private BigDecimal remainAmount;
    private String remark;
}
