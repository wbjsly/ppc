package com.erp.entity.crm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 商机线索（spec crm-lead-management，FR-4.8-1-1~1-5）。
 * BR-4.8-08 单人锁定：OWNER_ID 唯一负责，第二人认领由服务层拒绝。
 */
@Getter
@Setter
@TableName("erp_crm_lead")
public class Lead extends BaseEntity {

    public static final String ST_OPEN = "OPEN";
    public static final String ST_CONVERTED = "CONVERTED";
    public static final String ST_CLOSED = "CLOSED";
    public static final String ST_RECYCLED = "RECYCLED";

    /** 来源渠道（FR-4.8-1-1 预设枚举） */
    public static final String[] CHANNELS = {"EXPO", "WEB", "CHANNEL", "REFERRAL", "OUTBOUND"};

    private String leadNo;
    private String sourceChannel;
    private String companyName;
    private String contactName;
    private String contactPhone;
    private String demandDesc;
    private BigDecimal expectAmount;
    private LocalDate expectCloseDate;

    /** 五维评分 1-5；NULL = 未评（按 0 分计并 L4 提示） */
    private Integer scoreNeed;
    private Integer scoreBudget;
    private Integer scoreChain;
    private Integer scoreUrgency;
    private Integer scoreCompete;

    private BigDecimal score;
    private String grade;
    private String scoreModelId;
    private Integer scoreModelVersion;
    private LocalDateTime scoreAt;

    private String ownerId;
    private String ownerName;
    private LocalDateTime assignedAt;
    /** 1 = 在线索池待指派 */
    private String poolFlag;
    private LocalDateTime lastFollowupAt;
    /** 超 3 工作日未跟进的最近提醒时间（FR-4.8-1-3） */
    private LocalDateTime remindAt;
    private LocalDate nextPlanDate;

    private String status;
    private String convertedOppId;
    /** 查重命中的既有线索 ID（关联标记） */
    private String duplicateOf;
    private String remark;
}
