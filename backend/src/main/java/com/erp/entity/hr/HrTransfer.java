package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 人事变动：转正/调岗（双人复核 C-4.7-03，design D2）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_transfer")
public class HrTransfer extends BaseEntity {

    public static final String TYPE_PROBATION = "PROBATION";
    public static final String TYPE_POST = "POST";

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_DEPT_OUT_CONFIRMED = "DEPT_OUT_CONFIRMED";
    public static final String ST_DEPT_IN_CONFIRMED = "DEPT_IN_CONFIRMED";
    public static final String ST_HR_REVIEWED = "HR_REVIEWED";
    public static final String ST_REVIEWING = "REVIEWING";
    public static final String ST_REVIEW_DIFF = "REVIEW_DIFF";
    public static final String ST_APPROVING = "APPROVING";
    public static final String ST_EFFECTED = "EFFECTED";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_TERMINATED = "TERMINATED";
    public static final String ST_AUTO_REGULAR = "AUTO_REGULAR";

    /** 变动单号 TRF+流水 */
    private String trfNo;
    /** PROBATION 转正 / POST 调岗 */
    private String type;
    private String empId;
    private String empName;

    // 转正
    private Integer abilityScore;
    private Integer attitudeScore;
    private String attitudeNote;
    /** 按期转正 REGULAR / 延期 EXTEND / 终止 TERMINATE */
    private String proposal;
    private Integer extendMonths;

    // 调岗
    private String fromDeptId;
    private String fromPosId;
    private String toDeptId;
    private String toPosId;
    private String salaryAdvice;
    /** 带宽超限 1=超上限/0=低于下限/NULL 未触发（FR-4.7-4-5 L2，add-org-management） */
    private String bandExceeded;
    /** 带宽校验说明（超限详情/未关联职级标注） */
    private String bandNote;
    /** 1=变更社保缴纳地（HR 审核环节勾选，add-transfer-followup FR-4.7-4-8） */
    private String placeChange;
    /** 新社保缴纳地（值域=社保政策表城市） */
    private String newPlace;
    /** 调薪后月薪（转正单可选，审批过写回主档 BASE_SALARY + change_log） */
    private java.math.BigDecimal newSalary;
    private LocalDate effectiveDate;

    private String reason;
    /** DRAFT→双部门确认→HR 审核→双人复核→审批→生效 */
    private String status;
    private String apprId;

    // 双人复核（两人独立五要素 JSON + 意见 + 时间）
    private String review1Json;
    private String review1By;
    private LocalDateTime review1At;
    private String review2Json;
    private String review2By;
    private LocalDateTime review2At;
    /** 差异项（不一致时挂起） */
    private String reviewDiffJson;

    /** 账号联动待处理标记（失败降级） */
    private String linkPending;
    private String linkNote;
}
