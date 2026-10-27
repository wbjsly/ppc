package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 有条件结项遗留跟踪工单（BR-4.8-28：90 天闭环，逾期升级 ROLE_PMO）。 */
@Getter
@Setter
@TableName("erp_pm_closure_remediation")
public class ClosureRemediation extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_OPEN = "OPEN";
    public static final String ST_DONE = "DONE";

    private String checkId;
    private String projectId;
    /** 遗留事项描述 */
    private String content;
    /** 来源检查项 key（自定义行遗留时回填） */
    private String sourceItemKey;
    private String ownerId;
    private String ownerName;
    /** 期限=提交日+90 天（BR-4.8-28） */
    private LocalDateTime dueDate;
    /** DRAFT 审批前草稿 | OPEN 生效跟踪 | DONE 已关闭 */
    private String status;
    /** 逾期已升级 ROLE_PMO（幂等） */
    private String escalatedFlag;
    private LocalDateTime escalatedAt;
    private String closeNote;
    private String closeBy;
    private LocalDateTime closeDate;
}
