package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 社保增减员/转移单（social-insurance-ops：事件自动开单 + 手工补开 + 申报登记）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_social_change")
public class HrSocialChange extends BaseEntity {
    public static final String TYPE_ADD = "ADD";
    public static final String TYPE_REMOVE = "REMOVE";
    public static final String TYPE_TRANSFER = "TRANSFER";

    public static final String SRC_ONBOARDING = "ONBOARDING";
    public static final String SRC_EXIT = "EXIT";
    public static final String SRC_TRANSFER = "TRANSFER";
    public static final String SRC_MANUAL = "MANUAL";

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_DECLARED = "DECLARED";
    public static final String ST_DONE = "DONE";
    public static final String ST_FAILED = "FAILED";
    public static final String ST_VOID = "VOID";

    private String chgNo;
    /** ADD/REMOVE/TRANSFER */
    private String chgType;
    private String empId;
    private String empName;
    private String empNo;
    /** 目标缴纳地（ADD/TRANSFER） */
    private String targetPlace;
    /** 触发源 ONBOARDING/EXIT/TRANSFER/MANUAL */
    private String srcType;
    /** 触发人事单号 */
    private String srcId;
    /** 申报日期 */
    private LocalDateTime declareDate;
    /** 回执号 */
    private String receiptNo;
    /** 经办人 */
    private String operator;
    /** 生效日期（REMOVE 缺省取离职日） */
    private LocalDate changeDate;
    /** DRAFT/DECLARED/DONE/FAILED/VOID */
    private String status;
    private String note;
}
