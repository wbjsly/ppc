package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 绩效评分（员工×周期，DRAFT→SUBMITTED→CALIBRATING→CALIBRATED→CONFIRMED）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_perf_score")
public class HrPerfScore extends BaseEntity {
    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_SUBMITTED = "SUBMITTED";
    public static final String ST_CALIBRATING = "CALIBRATING";
    public static final String ST_CALIBRATED = "CALIBRATED";
    public static final String ST_CONFIRMED = "CONFIRMED";

    private String planId;
    private String period;
    private String empId;
    private String empNo;
    private String empName;
    private String deptId;
    /** 分值 */
    private BigDecimal score;
    /** A/B/C/D */
    private String grade;
    /** 等级系数快照 */
    private BigDecimal coef;
    private String commentText;
    private String status;
    private LocalDateTime submitAt;
    private LocalDateTime confirmAt;
    private String confirmBy;
}
