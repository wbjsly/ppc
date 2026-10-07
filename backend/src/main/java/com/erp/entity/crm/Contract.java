package com.erp.entity.crm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 销售合同（tasks 10.8/14.x，spec sales-contract，design D11 与框架协议独立）。
 * 合同编号创建后不可改；收款计划挂载主体；合同 → SO → 应收链路供计划达成对比。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_crm_contract")
public class Contract extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_APPROVING = "APPROVING";
    public static final String ST_LEGAL_REVIEW = "LEGAL_REVIEW";
    public static final String ST_SIGNED = "SIGNED";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_CHANGED = "CHANGED";
    public static final String ST_TERMINATED = "TERMINATED";

    private String contractNo;
    private String oppId;
    private String oppName;
    private String customerId;
    private String customerCode;
    private String customerName;
    private String title;
    /** 商机预期金额（差异校验基准） */
    private BigDecimal oppAmount;
    private BigDecimal amount;
    /** 差异率 = (AMOUNT − OPP_AMOUNT) / OPP_AMOUNT */
    private BigDecimal diffRate;
    private LocalDate signDate;
    /** 签订人（14.2，057 动态列） */
    private String signedBy;
    private LocalDateTime signedAt;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;
    private String prevStatus;
    private String approvalId;
    private String legalReviewBy;
    private String legalReviewOpinion;
    private LocalDateTime legalReviewAt;
    private Integer versionNo;
    /** 累计变更次数（14.6，057 动态列） */
    private Integer changeCount;
    private Integer soCount;
    private String terminateReason;
    private String closedBy;
    private LocalDateTime closedAt;
    private String remark;

    // ---------- 非持久化 ----------
    /** 差异 > 10% 需 L2 审批（服务层判定） */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private transient Boolean needApproval;
}
