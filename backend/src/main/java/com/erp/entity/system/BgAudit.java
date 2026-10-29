package com.erp.entity.system;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Break-Glass 事后审计报告（add-break-glass-console design D4，BR-5.6-08）。
 * 回收时刻自动生成（LEDGER_ID 唯一锚幂等）；安全管理员签署结论；24h 未签每 12h 告警。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_admin_bg_audit")
public class BgAudit extends BaseEntity {

    public static final String CONCLUSION_NO_VIOLATION = "NO_VIOLATION";
    public static final String CONCLUSION_VIOLATION = "VIOLATION";

    @TableField("APPLY_ID")
    private String applyId;

    /** 授予台账行（生成锚点，唯一） */
    @TableField("LEDGER_ID")
    private String ledgerId;

    @TableField("TARGET_USER_ID")
    private String targetUserId;

    @TableField("GRANT_AT")
    private LocalDateTime grantAt;

    @TableField("RECLAIM_AT")
    private LocalDateTime reclaimAt;

    @TableField("LOG_COUNT")
    private Integer logCount;

    @TableField("CONCLUSION")
    private String conclusion;

    @TableField("SIGNED_BY")
    private String signedBy;

    @TableField("SIGNED_AT")
    private LocalDateTime signedAt;

    /** 月度 ≥3 次专项评审标记 */
    @TableField("SPECIAL_REVIEW")
    private String specialReview;

    /** 逾期告警幂等标记（12h 重复） */
    @TableField("LAST_REMIND_AT")
    private LocalDateTime lastRemindAt;
}
