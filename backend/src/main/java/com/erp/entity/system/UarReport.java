package com.erp.entity.system;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 季度 UAR 报告（add-access-review-console design D6，SOP-5.6-D step6）。
 * TASK_ID 唯一幂等；ADMIN 确认 + 内审会签双签归档（C-5.6-05）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_admin_uar_report")
public class UarReport extends BaseEntity {

    @TableField("TASK_ID")
    private String taskId;

    @TableField("TOTAL_ITEMS")
    private Integer totalItems;

    @TableField("JUDGED_ITEMS")
    private Integer judgedItems;

    @TableField("COVERAGE_RATE")
    private BigDecimal coverageRate;

    @TableField("RECLAIMED_ITEMS")
    private Integer reclaimedItems;

    @TableField("RECLAIM_RATE")
    private BigDecimal reclaimRate;

    @TableField("ANOMALY_JSON")
    private String anomalyJson;

    @TableField("PENDING_JSON")
    private String pendingJson;

    @TableField("CONFIRM_BY")
    private String confirmBy;

    @TableField("CONFIRM_AT")
    private LocalDateTime confirmAt;

    @TableField("SIGN_BY")
    private String signBy;

    @TableField("SIGN_AT")
    private LocalDateTime signAt;

    @TableField("ARCHIVED")
    private String archived;
}
