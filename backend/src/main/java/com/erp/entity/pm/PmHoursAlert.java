package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 工时偏差预警（add-timesheet-management，C-4.8-03；项目+级别唯一，已处理+升级标记）。 */
@Getter
@Setter
@TableName("erp_pm_hours_alert")
public class PmHoursAlert extends BaseEntity {

    /** 预警级别（BR-4.8-19 两级） */
    public static final String LV_YELLOW = "YELLOW";
    public static final String LV_RED = "RED";

    /** 项目 */
    private String projectId;
    /** 级别 */
    private String level;
    /** 触发时偏差率快照（%） */
    private BigDecimal deviationRate;
    /** 预警生成日 */
    private LocalDate alertDate;
    /** 已处理标记（PM 点按） */
    private String handledFlag;
    /** 处理人 */
    private String handledBy;
    /** 处理说明（可附） */
    private String handledNote;
    /** 处理时间 */
    private LocalDateTime handledAt;
    /** 5 工作日未处理已升级标记 */
    private String escalatedFlag;
    /** 升级时间 */
    private LocalDateTime escalatedAt;
}
