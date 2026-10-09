package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 销售报价单头（4.3 流程一 FR-4.3-1-1~1-8，spec sales-quote）。
 * 由商机入口创建（BR-4.3-07 引用商机编号不重复建档）；
 * 状态机：DRAFT → PENDING（负毛利锁定也在此态）→ PUBLISHED → CONVERTED；
 *        SUPERSEDED 被新版本替代（历史可查不可转）/ REJECTED 驳回可改重提。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_quote")
public class SdQuote extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PENDING = "PENDING";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_PUBLISHED = "PUBLISHED";
    public static final String ST_SUPERSEDED = "SUPERSEDED";
    public static final String ST_CONVERTED = "CONVERTED";

    public static final String TYPE_STANDARD = "STANDARD";
    public static final String TYPE_SAMPLE = "SAMPLE";

    private String draftNo;
    /** 正式报价编号（发布时生成，FR-4.3-1-7） */
    private String quoteNo;

    private String oppId;
    private String oppNo;
    private String customerId;
    private String customerCode;
    private String customerName;

    /** STANDARD 标准单 / SAMPLE 样品单（样品单豁免 MOQ） */
    private String orderType;
    private String contactName;
    private String shipAddress;
    private String remark;

    private BigDecimal totalAmount;
    private BigDecimal totalCost;
    /** 毛利率（小数，0.05 = 5%） */
    private BigDecimal marginRate;
    /** 低毛利二次确认留痕（不构成价格授权） */
    private String marginConfirmBy;
    private LocalDateTime marginConfirmAt;
    /** 《价格协议匹配记录》JSON：命中/未命中清单 */
    private String priceMatch;

    private Integer versionNo;
    /** 被哪个新版本替代（旧版本不可转 SO） */
    private String supersededBy;

    private LocalDate validFrom;
    private LocalDate validTo;

    private String status;
    private String approvalId;
    private String soId;
    private String soNo;
    private LocalDateTime convertedAt;
    private String publishBy;
    private LocalDateTime publishAt;

    /** 是否已过有效期（PUBLISHED 且 validTo < 今天） */
    public boolean isExpired() {
        return ST_PUBLISHED.equals(status) && validTo != null && validTo.isBefore(LocalDate.now());
    }
}
