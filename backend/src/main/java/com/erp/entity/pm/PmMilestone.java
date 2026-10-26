package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 项目里程碑（add-milestone-management，FR-4.8-3-1；挂 WBS 阶段节点 level=1）。 */
@Getter
@Setter
@TableName("erp_pm_milestone")
public class PmMilestone extends BaseEntity {

    /** 六态状态机 */
    public static final String ST_PLANNED = "PLANNED";
    public static final String ST_SUBMITTING = "SUBMITTING";
    public static final String ST_REVIEWING = "REVIEWING";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_DONE = "DONE";
    public static final String ST_REWORK = "REWORK";

    /** 项目 ID */
    private String projectId;
    /** 挂载 WBS 阶段节点（level=1 校验） */
    private String wbsId;
    /** 里程碑名称 */
    private String name;
    /** 计划完成日期 */
    private LocalDate planEndDate;
    /** 量化评审标准（文本） */
    private String reviewCriteria;
    /** 审批人（展示用） */
    private String approverUserId;
    /** 审批人姓名（冗余展示） */
    private String approverName;
    /** 前序依赖里程碑（同项目，可空；BR-4.8-16） */
    private String prevMilestoneId;
    /** 状态 */
    private String status;
    /** 返工计数（FAIL +1；==2 升级 GM） */
    private Integer reworkCount;
    /** 评审通过时间（审批超时基准） */
    private LocalDateTime reviewPassAt;
    /** 实际完成日（DONE 时写） */
    private LocalDate actualEndDate;
    /** L4 滞后已通知标记 */
    private String delayNotified;
    /** 升级滞后已通知标记 */
    private String escalatedNotified;
    /** 审批催办已标记 */
    private String overdueReminded;
    /** 审批实例 ID */
    private String apprId;
    /** 审批驳回意见 */
    private String rejectOpinion;
}
