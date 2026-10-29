package com.erp.entity.system;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 季度最小权限复核任务（add-access-review-console design D1，SOP-5.6-D step1）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_admin_uar_task")
public class UarTask extends BaseEntity {

    public static final String ST_REVIEWING = "REVIEWING";
    public static final String ST_COMPLETED = "COMPLETED";
    public static final String ST_ARCHIVED = "ARCHIVED";

    @TableField("CYCLE_TAG")
    private String cycleTag;

    @TableField("DUE_AT")
    private LocalDateTime dueAt;

    @TableField("STATUS")
    private String status;

    @TableField("ESCALATED_AT")
    private LocalDateTime escalatedAt;

    @TableField("REMIND_AT")
    private LocalDateTime remindAt;
}
