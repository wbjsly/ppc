package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/** 提醒记录（BR-4.7-08 转正 15 天；同员工同日 UK 防重复轰炸）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_remind")
public class HrRemind extends BaseEntity {

    public static final String TYPE_PROBATION = "PROBATION";
    /** 转正 7 天二次提醒（抄送 HR 经理，add-transfer-followup FR-4.7-4-1） */
    public static final String TYPE_PROBATION_SECOND = "PROBATION_SECOND";
    /** 跨城市调岗社保转移提醒（FR-4.7-4-8） */
    public static final String TYPE_SOCIAL_TRANSFER = "SOCIAL_TRANSFER";
    /** 借调到期返岗提醒（FR-4.7-7-6，add-employee-secondment） */
    public static final String TYPE_SECONDMENT_RETURN = "SECONDMENT_RETURN";
    /** 借调过期未返岗告警（L1 阻断分摊） */
    public static final String TYPE_SECONDMENT_EXPIRED = "SECONDMENT_EXPIRED";
    /** 加班审批超时催办（FR-4.7-6-2，EMP_ID=审批主管） */
    public static final String TYPE_OT_APPROVE_OVERDUE = "OT_APPROVE_OVERDUE";
    /** 资格认证复审提醒（BR-4.7-06，add-training-certification） */
    public static final String TYPE_CERT_REVIEW = "CERT_REVIEW";
    /** 培训任务临期/逾期催办（training-plan-execution） */
    public static final String TYPE_TRAIN_TASK = "TRAIN_TASK";
    /** 里程碑审批超时催办（FR-4.8-3-4，3 工作日未审提醒 PMO） */
    public static final String TYPE_MS_APPROVE_OVERDUE = "MS_APPROVE_OVERDUE";
    /** 里程碑进度滞后升级催办（FR-4.8-3-5，滞后 >15 工作日） */
    public static final String TYPE_MS_DELAY = "MS_DELAY";
    /** 工时连续未提交催办（FR-4.8-4-2 异常处理，连续 3 天提醒项目经理） */
    public static final String TYPE_HOURS_MISSING = "HOURS_MISSING";
    /** 工时偏差纠偏超期升级（FR-4.8-4-5，预警 5 工作日未处理升级 PMO） */
    public static final String TYPE_HOURS_ESCALATE = "HOURS_ESCALATE";

    /** PROBATION 转正提醒等 */
    private String remindType;
    private String empId;
    private String empName;
    /** 提醒日 */
    private LocalDate remindDate;
    private String detail;
}
