package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 客户质量协议（change rework-inspection-tasks，spec customer-quality-agreement）。
 * 客户 × 物料/品类维度；加严 CTQ 清单 + 抽样档位覆盖 + 连续投诉计数（≥2 → OQC 全检）。
 * 停用（STOPPED）仅阻止新批命中，历史批快照不变；禁止物理删除。
 */
@Getter
@Setter
@TableName("erp_qms_customer_agreement")
public class CustomerAgreement extends BaseEntity {

    /** 协议编码（唯一，创建后不可改） */
    @TableField("AGREE_CODE")
    private String agreeCode;

    /** 客户 ID */
    @TableField("CUSTOMER_ID")
    private String customerId;

    /** 客户名称（快照） */
    @TableField("CUSTOMER_NAME")
    private String customerName;

    /** ITEM / CATEGORY（二选一必填） */
    @TableField("SCOPE_TYPE")
    private String scopeType;

    /** 物料编码或品类编码 */
    @TableField("SCOPE_VALUE")
    private String scopeValue;

    /** 加严 CTQ 清单 JSON [{name,specType,lower,upper,unit,method}] */
    @TableField("TIGHT_CTQ_JSON")
    private String tightCtqJson;

    /** 抽样档位覆盖 A/B/C（NULL = 不覆盖，与风险档取严） */
    @TableField("SAMPLE_LEVEL_OVERRIDE")
    private String sampleLevelOverride;

    /** 连续投诉计数（≥2 → 该客户 OQC 自动全检） */
    @TableField("COMPLAINT_STREAK")
    private Integer complaintStreak;

    @TableField("EFFECTIVE_FROM")
    private LocalDate effectiveFrom;

    @TableField("EFFECTIVE_TO")
    private LocalDate effectiveTo;

    /** DRAFT / ACTIVE / EXPIRED / STOPPED */
    @TableField("STATUS")
    private String status;

    @TableField("REMARK")
    private String remark;
}
