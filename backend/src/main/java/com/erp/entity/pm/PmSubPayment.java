package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 外协付款单（add-cost-collection-freeze，FR-4.8-5-2）。 */
@Getter
@Setter
@TableName("erp_pm_sub_payment")
public class PmSubPayment extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_SUBMITTED = "SUBMITTED";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";

    /** 付款单号 SPP+yyyyMMdd-序号 */
    private String payNo;
    private String contractId;
    private String projectId;
    /** 合同冗余（空则归项目级） */
    private String wbsId;
    private java.math.BigDecimal amount;
    private java.time.LocalDate payDate;
    /** DRAFT|SUBMITTED|APPROVED|REJECTED */
    private String status;
    private String reviewBy;
    private java.time.LocalDateTime reviewDate;
    private String rejectReason;
    private String voucherId;
}
