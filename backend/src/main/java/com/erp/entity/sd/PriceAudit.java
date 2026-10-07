package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 价格审计日志（C-4.3-04 / BR-4.3-35/36）：
 * 只增不改、全计算过程留痕、保留期参数化（AUDIT_RETENTION_DAYS）、关联 SO 行号。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_price_audit")
public class PriceAudit extends BaseEntity {

    public static final String SRC_SO = "SO";
    public static final String SRC_QUOTE = "QUOTE";
    public static final String SRC_CALC = "CALC";

    private String srcType;
    private String srcId;
    private Integer lineNo;
    private String customerId;
    private String customerCode;
    private String itemCode;
    private BigDecimal qty;

    /** 原始基准价（协议试算价） */
    private BigDecimal basePrice;
    private BigDecimal channelRate;
    /** 量价阶梯层折算率（1 - 阶梯价/基准价） */
    private BigDecimal ladderRate;
    private BigDecimal promoRate;
    /** BEST_SINGLE / STACKED */
    private String stackMode;
    /** 实际采用来源 CHANNEL/LADDER/PROMO/PROTOCOL/NONE */
    private String appliedSource;
    /** 最终生效价 */
    private BigDecimal finalPrice;
    /** 冲突来源与裁决（最后生效价）JSON */
    private String conflictJson;
    /** 未采用来源清单 JSON */
    private String rejectedJson;
    /** 特批单号（毛利阻断放行时回写） */
    private String specialApprovalNo;
    private String operatorId;
    private LocalDateTime operateAt;
    /** 保留到期日 */
    private LocalDate retentionUntil;
}
