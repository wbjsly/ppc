package com.erp.entity.i18n;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 语言包版本（SOP-5.7-B 不可变版本包） */
@Data
@TableName("erp_i18n_pack")
public class I18nPack implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PRECHECK = "PRECHECK";
    public static final String ST_REVIEWING = "REVIEWING";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_CANARY = "CANARY";
    public static final String ST_ARCHIVED = "ARCHIVED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String packNo;

    private String locale;

    private String status;

    private String sourceVersion;

    private String fallbackPolicyJson;

    private Integer canaryPct;

    private Integer canaryWindowH;

    private Integer renderErrCnt;

    private Integer renderReqCnt;

    private String remark;

    private String createBy;

    private LocalDateTime createDate;

    private String updateBy;

    private LocalDateTime updateDate;
}
