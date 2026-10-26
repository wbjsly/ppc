package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 项目头（project-initiation，FR-4.8-2-1；11.3.1）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_pm_project")
public class PmProject extends BaseEntity {

    public static final String TYPE_RD = "研发";
    public static final String TYPE_IMPL = "实施";
    public static final String TYPE_OPS = "运维";

    public static final String PATH_EXT = "EXT";
    public static final String PATH_INTERNAL = "INTERNAL";

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_APPROVING = "APPROVING";
    public static final String ST_ACTIVE = "ACTIVE";

    /** 1=冻结（实际累计成本超预算基线，BR-4.8-21） */
    public static final String FREEZE_YES = "1";
    public static final String FREEZE_NO = "0";

    /** PRJ+yyyy+4位流水，基线审批通过时生成，生成后不可改 */
    private String projectNo;
    private String name;
    /** 研发/实施/运维 */
    private String projType;
    /** EXT 外部交付 / INTERNAL 内部项目 */
    private String deliveryPath;
    /** EXT 必填，关联 erp_crm_contract */
    private String contractId;
    private String contractNo;
    /** INTERNAL 必填，内部立项审批单号（自由文本） */
    private String internalFormNo;
    /** 项目经理（登录账号） */
    private String pmUserId;
    private String pmName;
    private String pmoUserId;
    private LocalDate startDate;
    private LocalDate endDate;
    private String goal;
    private String scope;
    /** DRAFT/APPROVING/ACTIVE */
    private String status;
    /** 基线审批实例 */
    private String apprId;
    private String rejectOpinion;
    /** 1=基线锁定（审批通过） */
    private String baselineLocked;
    private BigDecimal baselineAmount;
    /** 间接分摊率 */
    private BigDecimal fenplanRate;
    /** 项目级费率覆盖 JSON {grade:rate} */
    private String rateOverride;
    private String startNotified;
    /** 1=冻结（实际累计成本超预算基线，BR-4.8-21；add-cost-collection-freeze） */
    private String freezeFlag;
    /** 冻结原因 */
    private String freezeReason;
    /** 冻结时间 */
    private java.time.LocalDateTime frozenAt;
}
