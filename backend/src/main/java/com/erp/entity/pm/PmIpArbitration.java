package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** IP 权属冲突仲裁单（add-ip-management，C-4.8-04 双入口防重）。 */
@Getter
@Setter
@TableName("erp_pm_ip_arbitration")
public class PmIpArbitration extends BaseEntity {

    /** IAR+yyyyMMdd-序号 */
    private String arbNo;
    private String achieveId;
    /** CONFIRM_OBJECTION 确认异议带出|MANUAL 法务独立申请 */
    private String source;
    /** CLAIMS_SUBMITTED|LEGAL_REVIEWING|OPINION_ISSUED|PARTY_CONFIRMED|ADJUDICATED|ESCALATED_EXTERNAL */
    private String status;
    private String opinion;
    private String opinionBy;
    private java.time.LocalDateTime opinionDate;
    /** 裁决权属 JSON（回写成果覆盖原判定） */
    private String adjudicateResult;
    private String escalateReason;
    private String confirmBy;
    private java.time.LocalDateTime confirmDate;

    public static final String ST_CLAIMS_SUBMITTED = "CLAIMS_SUBMITTED";
    public static final String ST_LEGAL_REVIEWING = "LEGAL_REVIEWING";
    public static final String ST_OPINION_ISSUED = "OPINION_ISSUED";
    public static final String ST_PARTY_CONFIRMED = "PARTY_CONFIRMED";
    public static final String ST_ADJUDICATED = "ADJUDICATED";
    public static final String ST_ESCALATED_EXTERNAL = "ESCALATED_EXTERNAL";
    public static final String SRC_CONFIRM_OBJECTION = "CONFIRM_OBJECTION";
    public static final String SRC_MANUAL = "MANUAL";
}
