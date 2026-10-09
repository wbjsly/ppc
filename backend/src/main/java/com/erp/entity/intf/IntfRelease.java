package com.erp.entity.intf;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 生产放行单与观察期（SOP-5.5-A 步骤7/8，C-0-03 发起人≠复核人）。 */
@Getter
@Setter
@TableName("erp_intf_release")
public class IntfRelease extends BaseEntity {

    public static final String ST_PENDING = "PENDING";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_TRIAL = "TRIAL";
    public static final String ST_FORMAL = "FORMAL";
    public static final String ST_ARCHIVED = "ARCHIVED";

    private String releaseNo;
    private String contractId;
    private String partnerCode;
    private String channelId;
    private BigDecimal passRate;
    private String securityNote;
    private String receiptSample;
    private String status;
    private String initiatedBy;
    private LocalDateTime initiatedAt;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private LocalDateTime reviewDueAt;
    private String prodKeyId;
    private LocalDate trialStart;
    private LocalDate trialEnd;
    private BigDecimal trialErrRate;
    private Integer trialLimitHits;
    private LocalDateTime archiveAt;
    private String remark;
}
