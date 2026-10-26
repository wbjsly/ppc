package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 年度培训计划（training-plan-execution，编制即发布，变更留痕）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_train_plan")
public class HrTrainPlan extends BaseEntity {

    public static final String ST_PUBLISHED = "PUBLISHED";
    public static final String ST_CANCELLED = "CANCELLED";

    public static final String TARGET_DEPT = "DEPT";
    public static final String TARGET_MEMBERS = "MEMBERS";

    /** TP+年度+流水，创建后不可改 */
    private String planNo;
    private Integer planYear;
    private String courseName;
    /** 内训/外训/线上 */
    private String trainType;
    /** 关联技能（培训通过写入技能矩阵） */
    private String skillCode;
    /** 通过后授予等级 */
    private String skillLevel;
    /** DEPT=按部门快照 / MEMBERS=人员清单 */
    private String targetType;
    /** DEPT:部门ID / MEMBERS:逗号分隔员工ID */
    private String targetValue;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    /** 完成期限（催办基准） */
    private LocalDate dueDate;
    private BigDecimal planHours;
    /** 合格线（缺省 TRAIN_PASS_SCORE） */
    private Integer passScore;
    private BigDecimal budget;
    /** 实际费用（登记触发计提凭证） */
    private BigDecimal actualCost;
    /** 负责 HR */
    private String owner;
    private String status;
    /** 修改留痕 JSON（时间/人/字段/前后值） */
    private String changeLogJson;
}
