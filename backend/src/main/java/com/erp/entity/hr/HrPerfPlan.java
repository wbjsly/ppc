package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 绩效方案（performance-management：周期+等级定义+系数映射，评分期内系数锁定）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_perf_plan")
public class HrPerfPlan extends BaseEntity {
    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_CLOSED = "CLOSED";
    public static final String ST_VOID = "VOID";

    /** PL+yyyyMMdd-序号 */
    private String planNo;
    private String name;
    /** 适用部门（空=全公司） */
    private String deptId;
    /** YEAR/HALF/MONTH */
    private String periodType;
    /** 周期标识 2026H2/2026-10 */
    private String period;
    /** 等级定义 JSON [{grade,coef,minScore,maxScore}] */
    private String gradeJson;
    private String status;
    private LocalDateTime closeAt;
    /** 同周期同部门唯一 UK（DEPT_ID 空用 ALL） */
    private String ukPerm;
}
