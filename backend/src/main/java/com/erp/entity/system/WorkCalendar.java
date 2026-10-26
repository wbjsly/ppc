package com.erp.entity.system;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 全局工作日历（change add-dispatch-management，spec capacity-load「工作日历主数据与种子」，迁移 122）。
 * 全局主数据挂系统管理 16.11.1：日期唯一，IS_WORKDAY 1 工作/0 非工作（节假日/调休在此反转），
 * 2026—2027 已随迁移种子；缺失日期由 KITTING_CALENDAR_FALLBACK 降级判定（禁止静默全可用/全不可用）。
 */
@Getter
@Setter
@TableName("erp_admin_work_calendar")
public class WorkCalendar extends BaseEntity {

    private LocalDate calDate;
    /** 1 工作日 / 0 非工作日 */
    private String isWorkday;
    /** 节假日/调休备注 */
    private String remark;
}
