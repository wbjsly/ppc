package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 参训任务（UK=PLAN_ID+EMP_ID，重复展开幂等）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_train_task")
public class HrTrainTask extends BaseEntity {

    public static final String ST_PENDING = "PENDING";
    public static final String ST_CONFIRMED = "CONFIRMED";
    public static final String ST_COMPLETED = "COMPLETED";
    public static final String ST_FAILED = "FAILED";
    public static final String ST_CANCELLED = "CANCELLED";

    private String planId;
    private String empId;
    private String empName;
    private String empNo;
    /** 展开时部门快照（完成率部门维度） */
    private String deptId;
    private String status;
    /** 出勤：正常/缺勤/迟到 */
    private String attend;
    /** 考试成绩 */
    private BigDecimal score;
    /** 实际学时 */
    private BigDecimal actualHours;
    private String remark;
    /** 员工自助确认时间 */
    private LocalDateTime confirmAt;
    /** SELF（员工确认） */
    private String confirmSrc;
    /** HR 登记人 */
    private String resultBy;
    private LocalDateTime resultAt;
    /** 结果修改留痕 JSON（前后成绩/学时/人/时间） */
    private String resultHistJson;
}
