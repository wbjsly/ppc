package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 绩效结果快照（CONFIRMED 后不可改，薪酬核算取数来源）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_perf_result")
public class HrPerfResult extends BaseEntity {
    private String period;
    private String empId;
    private String empNo;
    private String empName;
    private String deptId;
    private String grade;
    private BigDecimal coef;
    private String scoreId;
    private String confirmBy;
    private LocalDateTime confirmAt;
}
