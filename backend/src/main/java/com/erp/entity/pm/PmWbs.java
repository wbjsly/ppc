package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/** WBS 单表三层树（project-initiation，FR-4.8-2-2；11.3.2）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_pm_wbs")
public class PmWbs extends BaseEntity {

    public static final int LV_PHASE = 1;
    public static final int LV_PACKAGE = 2;
    public static final int LV_TASK = 3;

    private String projectId;
    /** 层级编码 如 1.2.3 */
    private String wbsCode;
    private String parentId;
    /** 1 阶段 / 2 工作包 / 3 任务 */
    private Integer level;
    private String name;
    /** 负责人（任务级用于费率） */
    private String ownerUserId;
    private String ownerName;
    /** 职级（快照，费率计算） */
    private String ownerGrade;
    private LocalDate planStart;
    private LocalDate planEnd;
    /** 预估工时 */
    private BigDecimal estHours;
    /** 预估成本（展示用） */
    private BigDecimal estCost;
    /** 交付物定义（level2 必填） */
    private String deliverable;
    /** 阶段完成标记（里程碑全 DONE 由回调置 1；add-milestone-management） */
    private String phaseDone;
    /** 阶段完成日期 */
    private java.time.LocalDate phaseDoneDate;
    private Integer sortNo;
}
