package com.erp.entity.vmi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 领料单头（4.5.2，spec material-issue：DRAFT → POSTED | CANCELLED）。 */
@Getter
@Setter
@TableName("erp_inv_material_issue")
public class MaterialIssue extends BaseEntity {

    public static final String TYPE_OWN = "OWN";
    public static final String TYPE_VMI = "VMI";
    /** 售后领料（add-service-order：服务工单换件，头必填 serviceOrderNo，NORMAL 仓扣减） */
    public static final String TYPE_AFTER_SALE = "AFTER_SALE";
    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_POSTED = "POSTED";
    public static final String ST_CANCELLED = "CANCELLED";

    /** 领料单号 MI+yyyyMMdd+流水 */
    private String issueNo;
    /** OWN 自有 / VMI 寄售 / AFTER_SALE 售后 */
    private String issueType;
    /** 寄售供应商（VMI 领用必填） */
    private String supplierId;
    private String supplierName;
    /** 领用工单号（BR-4.2-37 逐笔记录） */
    private String workOrderNo;
    /** 关联服务工单号（AFTER_SALE 必填，add-service-order spec material-issue ADDED） */
    private String serviceOrderNo;
    private String dept;
    private String purpose;
    /** DRAFT / POSTED / CANCELLED */
    private String status;
    private String cancelReason;
    /** 《寄售转自有凭证》VT+yyyyMMdd+流水（仅 VMI） */
    private String transferDocNo;
    private String postBy;
    private LocalDateTime postDate;
    private String remark;
}
