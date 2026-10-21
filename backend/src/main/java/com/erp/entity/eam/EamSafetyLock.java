package com.erp.entity.eam;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 安全停机挂锁（add-asset-maintenance，design D1/D4，BR-4.13-16 L1）：
 * 锁编号审计（挂锁人/时间/解除人/时间）；ACTIVE 锁阻断派工/调拨/处置。
 */
@Getter
@Setter
@TableName("erp_eam_safety_lock")
public class EamSafetyLock extends BaseEntity {

    /** LOCK + yyyyMMdd + - + 4 位流水 */
    @TableField("LOCK_NO")
    private String lockNo;

    @TableField("CARD_ID")
    private String cardId;

    @TableField("CARD_NO")
    private String cardNo;

    @TableField("REASON")
    private String reason;

    @TableField("LOCK_BY")
    private String lockBy;

    @TableField("LOCK_TIME")
    private LocalDateTime lockTime;

    /** ACTIVE / RELEASED */
    @TableField("STATUS")
    private String status;

    @TableField("RELEASE_BY")
    private String releaseBy;

    @TableField("RELEASE_TIME")
    private LocalDateTime releaseTime;

    @TableField("RELEASE_REASON")
    private String releaseReason;

    /** 来源工单（可空=工具页手动） */
    @TableField("SOURCE_WO_NO")
    private String sourceWoNo;

    @TableField("REMARK")
    private String remark;
}
