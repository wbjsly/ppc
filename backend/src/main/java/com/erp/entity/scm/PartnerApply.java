package com.erp.entity.scm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 伙伴引入申请单（add-partner-onboarding，表 erp_scm_partner_apply，FR-4.9-1-1）。
 * 状态机：DRAFT → APPROVING → APPROVED / REJECTED / CLOSED（驳回≥3 次自动 CLOSED）。
 * 风险判定结果（RISK_LEVEL/KEY_MATERIAL_FLAG）提交时落值快照，审核期间不随主数据漂移。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_scm_partner_apply")
public class PartnerApply extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_APPROVING = "APPROVING";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_CLOSED = "CLOSED";

    public static final String RISK_HIGH = "HIGH";
    public static final String RISK_NORMAL = "NORMAL";

    /** 驳回自动关闭阈值（BR-4.9-10 语义：驳回≥3 次自动关闭） */
    public static final int REJECT_CLOSE_THRESHOLD = 3;

    /** PA+yyyyMMdd+流水 */
    private String applyNo;

    /** SUPPLIER 本期 | CUSTOMER 预留 */
    private String partnerType;

    private String supplierName;

    /** 统一社会信用代码（18 位 L1 校验 + UK 重复阻断） */
    private String taxNo;

    private String contactName;

    private String contactPhone;

    /** 激活链接送达邮箱 */
    private String contactEmail;

    /** 主营品类 */
    private String mainCategory;

    /** 预估年采购额（风险判定输入） */
    private BigDecimal estAnnualAmount;

    /** 关键物料判定结果快照 1|0 */
    private String keyMaterialFlag;

    /** HIGH 三级 | NORMAL 两级 */
    private String riskLevel;

    private String bankName;

    /** 付款条件（建档必填四组字段之一） */
    private String paymentTerms;

    private String status;

    /** 驳回累计（≥3 自动关闭） */
    private Integer rejectCount;

    /** 审核通过自动建档回写 */
    private String supplierId;

    /** 待激活门户账号回写 */
    private String accountId;

    private String approvalId;

    private String closeReason;

    private String remark;
}
