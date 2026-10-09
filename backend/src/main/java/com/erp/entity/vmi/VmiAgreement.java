package com.erp.entity.vmi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** VMI 寄售协议头（FR-4.2-8-1，spec vmi-consignment）。 */
@Getter
@Setter
@TableName("erp_proc_vmi_agreement")
public class VmiAgreement extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_EFFECTIVE = "EFFECTIVE";
    public static final String ST_EXPIRED = "EXPIRED";
    public static final String ST_DISABLED = "DISABLED";
    public static final String CYCLE_WEEK = "WEEK";
    public static final String CYCLE_MONTH = "MONTH";

    /** 协议编号 VM+yyyyMMdd+流水 */
    private String agreeNo;
    private String supplierId;
    private String supplierName;
    /** 结算周期 WEEK 周 / MONTH 月 */
    private String settleCycle;
    /** 物权转移时点（本期仅 ISSUE 领用时转移） */
    private String transferTime;
    private LocalDate effectiveDate;
    /** 失效日期（下达卡控须 >= PO 交期，BR-4.2-35） */
    private LocalDate expireDate;
    /** DRAFT / EFFECTIVE / EXPIRED / DISABLED */
    private String status;
    private String remark;
}
