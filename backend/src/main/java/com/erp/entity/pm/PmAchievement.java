package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 研发成果（add-ip-management，确认后永久保留 BR-4.8-26）。 */
@Getter
@Setter
@TableName("erp_pm_achievement")
public class PmAchievement extends BaseEntity {

    /** IPR+yyyyMMdd-序号 */
    private String achieveNo;
    private String projectId;
    /** 任务级节点（L1 硬阻断 C-4.8-07） */
    private String wbsId;
    /** PATENT|SOFT_CP|TRADE_SECRET|TECH_DOC */
    private String type;
    private String name;
    /** <20 字 L4 提示（不阻断） */
    private String description;
    private java.time.LocalDate completeDate;
    /** 权属判定 JSON {company:60,partner:30}（裁决可覆盖） */
    private String ownershipResult;
    /** DRAFT|PENDING_CONFIRM|CONFIRMED|LEGAL_REVIEW */
    private String status;
    /** 1=仲裁在途（防重依据） */
    private String arbitratingFlag;
    private String confirmBy;
    private java.time.LocalDateTime confirmDate;
    /** OBJ_MULTI 多方主张|OBJ_UNILATERAL 单方争议 */
    private String objectionType;
    private String rejectReason;

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PENDING_CONFIRM = "PENDING_CONFIRM";
    public static final String ST_CONFIRMED = "CONFIRMED";
    public static final String ST_LEGAL_REVIEW = "LEGAL_REVIEW";
    public static final String OBJ_MULTI = "OBJ_MULTI";
    public static final String OBJ_UNILATERAL = "OBJ_UNILATERAL";
    public static final String TYPE_PATENT = "PATENT";
    public static final String TYPE_SOFT_CP = "SOFT_CP";
    public static final String TYPE_TRADE_SECRET = "TRADE_SECRET";
    public static final String TYPE_TECH_DOC = "TECH_DOC";
}
