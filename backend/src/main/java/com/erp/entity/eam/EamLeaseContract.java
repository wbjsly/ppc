package com.erp.entity.eam;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 租赁合同（add-asset-transfer-lease，design D1，IFRS 16 承租人视角）：
 * 录入 → 分类（人工确认）→ 使用权确认（PV+计划+ROU 建卡）→ 月度处理 → 重计量/到期。
 * 短期低值豁免（BR-4.13-25）：EXEMPT 不建卡不出计划，直线进损益留痕。
 */
@Getter
@Setter
@TableName("erp_eam_lease_contract")
public class EamLeaseContract extends BaseEntity {

    /** LSE + yyyyMMdd + - + 4 位流水 */
    @TableField("LSE_NO")
    private String lseNo;

    @TableField("LESSOR")
    private String lessor;

    @TableField("ASSET_DESC")
    private String assetDesc;

    @TableField("START_DATE")
    private LocalDate startDate;

    @TableField("END_DATE")
    private LocalDate endDate;

    /** 租期月数（引擎取用） */
    @TableField("TERMS_MONTHS")
    private Integer termsMonths;

    /** 每期租金付款额 */
    @TableField("PAYMENT_AMOUNT")
    private BigDecimal paymentAmount;

    /** 付款周期月数（1/3/6/12） */
    @TableField("PAY_CYCLE_MONTHS")
    private Integer payCycleMonths;

    /** 年折现率（小数 0.045） */
    @TableField("DISCOUNT_RATE")
    private BigDecimal discountRate;

    /** INCLUSIVE 租赁内含利率 / INCREMENTAL 增量借款利率（取值依据留痕） */
    @TableField("RATE_BASIS")
    private String rateBasis;

    @TableField("RENEW_OPTION")
    private String renewOption;

    @TableField("BUY_OPTION")
    private String buyOption;

    @TableField("BUY_PRICE")
    private BigDecimal buyPrice;

    /** 租金指数浮动 1=有（重估付款行不改利率） */
    @TableField("INDEX_FLOAT_FLAG")
    private String indexFloatFlag;

    /** ROU/FINANCE/OPERATING/EXEMPT */
    @TableField("CLASSIFY")
    private String classify;

    @TableField("CLASSIFY_BASIS")
    private String classifyBasis;

    @TableField("CONFIRM_BY")
    private String confirmBy;

    @TableField("CONFIRM_DATE")
    private LocalDate confirmDate;

    @TableField("EXEMPT_REASON")
    private String exemptReason;

    @TableField("INIT_DIRECT_COST")
    private BigDecimal initDirectCost;

    @TableField("PREPAID_RENT")
    private BigDecimal prepaidRent;

    @TableField("LEASE_INCENTIVE")
    private BigDecimal leaseIncentive;

    /** 租赁负债现值 */
    @TableField("PV")
    private BigDecimal pv;

    /** 使用权资产 = PV + 初始费用 + 预付 − 激励 */
    @TableField("ROU_AMOUNT")
    private BigDecimal rouAmount;

    @TableField("ROU_CARD_ID")
    private String rouCardId;

    /** 默示续租暂估 1=按原条款暂估计提（BR-4.13-44） */
    @TableField("ESTIMATED_FLAG")
    private String estimatedFlag;

    @TableField("APPR_ID")
    private String apprId;

    /** DRAFT/CLASSIFIED/CONFIRMED/MATURED/TERMINATED */
    @TableField("STATUS")
    private String status;

    /** 条款变更快照 JSON */
    @TableField("CHANGE_JSON")
    private String changeJson;

    @TableField("REMARK")
    private String remark;
}
