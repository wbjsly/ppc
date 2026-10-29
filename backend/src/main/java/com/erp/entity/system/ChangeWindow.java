package com.erp.entity.system;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 变更窗口（add-permission-approval-flow design D1/D5）：
 * 周期起止（星期 + 时刻区间），特权权限仅窗口内生效；16.4.1 窗口维护页复用。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_change_window")
public class ChangeWindow extends BaseEntity {

    @TableField("NAME")
    private String name;

    /** 1=周一 .. 7=周日（LocalDate.getDayOfWeek 口径） */
    @TableField("DAY_OF_WEEK")
    private Integer dayOfWeek;

    /** 窗口开始时刻（含），HH:mm:ss */
    @TableField("START_TIME")
    private String startTime;

    /** 窗口结束时刻（不含），HH:mm:ss */
    @TableField("END_TIME")
    private String endTime;

    /** 1 启用 / 0 停用 */
    @TableField("STATUS")
    private String status;
}
