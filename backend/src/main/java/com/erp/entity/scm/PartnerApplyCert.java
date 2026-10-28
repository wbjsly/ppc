package com.erp.entity.scm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 伙伴引入申请证照（add-partner-onboarding，表 erp_scm_partner_apply_cert，FR-4.9-1-2）。
 * 每行附件必传（ATTACH_COUNT 冗余，BIZ_TYPE=PARTNER_APPLY）；OCR 两列本期恒空预留。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_scm_partner_apply_cert")
public class PartnerApplyCert extends BaseEntity {

    public static final String T_LICENSE = "LICENSE";
    public static final String T_QMS = "QMS";
    public static final String T_ENV = "ENV";
    public static final String T_PERMIT = "PERMIT";
    public static final String T_OTHER = "OTHER";

    private String applyId;

    /** LICENSE 营业执照 | QMS 质量体系 | ENV 环保 | PERMIT 行业许可 | OTHER */
    private String certType;

    private String certNo;

    private LocalDate issueDate;

    /** 有效期（< 当天 BR-4.9-09 阻断） */
    private LocalDate expireDate;

    /** 附件数冗余（每行必传≥1） */
    private Integer attachCount;

    /** 预留：本期恒空（OCR 不做） */
    private String ocrStatus;

    /** 预留：本期恒空 */
    private String ocrExtractJson;
}
