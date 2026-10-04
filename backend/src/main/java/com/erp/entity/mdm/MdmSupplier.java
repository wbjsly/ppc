package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 供应商主数据（供方管理 1.4.1，表 erp_mdm_supplier，行 550 四组字段）。
 * 编码规则：SUP-0001（4 位流水），创建后不可修改。
 * 状态机：PENDING 待审核 / QUALIFIED 合格 / FROZEN 冻结 / DISABLED 停用 / CERT_EXPIRED 证照过期。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_mdm_supplier")
public class MdmSupplier extends BaseEntity {

    private String supplierCode;

    private String supplierName;

    // ---- 基本信息 ----

    private String address;

    private String contactName;

    private String contactPhone;

    /** 挂靠法人主体（可空 = 集团级供应商） */
    private String legalEntityId;

    // ---- 财务信息 ----

    private String bankName;

    private String taxNo;

    private String paymentTerms;

    // ---- 合规信息 ----

    /** CLEAR 未命中 / HIT 命中（HIT → 建档与变更双入口 422 硬阻断） */
    private String blacklistResult;

    /** ESG 评估等级（A/B/C/D，可空） */
    private String esgRating;

    // ---- 生命周期 ----

    /** PENDING / QUALIFIED / FROZEN / DISABLED / CERT_EXPIRED / MERGED（合并终态，024 扩展） */
    private String status;

    /** 最近一次审核通过/驳回原因 */
    private String reviewReason;

    /** 合并指向目标编码（STATUS=MERGED 时非空，BR-4.1-03 锁定标识） */
    private String mergedTo;

    /** 合并前状态（30 天回退恢复用，design D3） */
    private String preStatus;

    /** 变更原因（UPDATE 时必填），非持久化，写入版本记录 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private transient String changeReason;
}
