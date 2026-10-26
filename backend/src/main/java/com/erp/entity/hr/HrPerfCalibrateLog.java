package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 绩效校准日志（只增不改，BR 留痕）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_perf_calibrate_log")
public class HrPerfCalibrateLog extends BaseEntity {
    public static final String ACT_SUBMIT = "SUBMIT";
    public static final String ACT_CALIBRATE = "CALIBRATE";
    public static final String ACT_ADJUST = "ADJUST";
    public static final String ACT_REJECT = "REJECT";
    public static final String ACT_CONFIRM = "CONFIRM";

    private String scoreId;
    private String period;
    private String action;
    private String fromGrade;
    private String toGrade;
    private String reason;
    private String operator;
    private LocalDateTime opAt;
}
