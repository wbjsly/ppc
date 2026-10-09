package com.erp.entity.intf;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 接口契约与版本治理（spec interface-onboarding 6.1/6.2）。 */
@Getter
@Setter
@TableName("erp_intf_contract")
public class IntfContract extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_REVIEWING = "REVIEWING";
    public static final String ST_PUBLISHED = "PUBLISHED";
    public static final String ST_DEPRECATED = "DEPRECATED";

    private String contractNo;
    private String name;
    private String partnerCode;
    private String protocol;
    private String msgStandard;
    private String endpointPath;
    private String fieldsJson;
    private String errorCodes;
    private String rateTier;
    private String idempotencyRule;
    private String desensitizeRule;
    private String version;
    private Integer majorVersion;
    private String status;
    private String compatLevel;
    private String prevVersion;
    private LocalDate keepUntil;
    private LocalDate sunsetAt;
    private LocalDateTime reviewDueAt;
    private Integer rejectCount;
    private String closeReason;
    private LocalDateTime publishAt;
    private String remark;
}
