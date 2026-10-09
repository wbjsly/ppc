package com.erp.entity.scm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 申诉（spec BR-4.9-07：7 工作日申诉期，复核成立出修正版重公示）。 */
@Getter
@Setter
@TableName("erp_scm_scorecard_appeal")
public class ScmScorecardAppeal extends BaseEntity {

    public static final String ST_SUBMITTED = "SUBMITTED";
    public static final String ST_CONFIRMED = "CONFIRMED";
    public static final String ST_REJECTED = "REJECTED";

    private String appealNo;
    private String resultId;
    private String supplierId;
    private String monthTag;
    private String reason;
    private String evidence;
    private String status;
    private String reviewer;
    private LocalDateTime reviewAt;
    private String conclusion;
    private String newResultId;
    private String remark;
}
