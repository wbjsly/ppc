package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 工时单（add-timesheet-management，FR-4.8-4-1；同人同天一单 UK）。 */
@Getter
@Setter
@TableName("erp_pm_timesheet")
public class PmTimesheet extends BaseEntity {

    /** 状态机 */
    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_BACKFILL = "BACKFILL";
    public static final String ST_SUBMITTED = "SUBMITTED";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";

    /** 填报日期 */
    private LocalDate workDate;
    /** 员工（登录账号映射） */
    private String empId;
    /** 员工姓名（冗余） */
    private String empName;
    /** 部门（展示） */
    private String deptId;
    /** 状态 */
    private String status;
    /** 单头合计（保存时重算） */
    private BigDecimal totalHours;
    /** 补录标记（BR-4.8-17 上周补录） */
    private String isBackfill;
    /** 补录审批实例（超 2 周 L2） */
    private String backfillApprId;
    /** 提交时间 */
    private LocalDateTime submitDate;
    /** 审核人 */
    private String reviewBy;
    /** 审核时间 */
    private LocalDateTime reviewDate;
    /** 驳回原因（必填） */
    private String rejectReason;
}
