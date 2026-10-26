package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 项目变更单（add-project-change，FR-4.8-8-1；全集容器，五类变更）。
 * 状态机：DRAFT → APPROVING →（驳回）REJECTED /（通过）EXECUTING → DONE → VERIFIED。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_pm_project_change")
public class PmProjectChange extends BaseEntity {

    public static final String TYPE_SCOPE = "SCOPE";
    public static final String TYPE_SCHEDULE = "SCHEDULE";
    public static final String TYPE_BUDGET = "BUDGET";
    public static final String TYPE_RESOURCE = "RESOURCE";
    public static final String TYPE_COMBO = "COMBO";

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_APPROVING = "APPROVING";
    public static final String ST_EXECUTING = "EXECUTING";
    public static final String ST_DONE = "DONE";
    public static final String ST_VERIFIED = "VERIFIED";
    public static final String ST_REJECTED = "REJECTED";

    /** PC+yyyyMMdd-序号 */
    private String changeNo;
    private String projectId;
    /** SCOPE|SCHEDULE|BUDGET|RESOURCE|COMBO */
    private String changeType;
    /** 客户需求|技术风险|外部环境|内部优化 */
    private String reason;
    /** 变更描述（<20 字 L4 提示，不阻断） */
    private String description;
    /** 期望生效日（早于今日=倒签 L1 拒绝，BR-4.8-32） */
    private LocalDate effectiveDate;
    /** 范围影响：WBS 增删改清单（人工） */
    private String scopeImpact;
    /** 进度影响：工作日（人工） */
    private Integer scheduleImpactDays;
    /** 资源影响：人力增减（人工） */
    private String resourceImpact;
    /** 变更追加金额 */
    private BigDecimal budgetAdd;
    /** 预算影响占比（服务端算 = 追加 / 基线） */
    private BigDecimal budgetRate;
    /** 1=涉合同（手动勾 OR 占比>10% 强制） */
    private String isContractFlag;
    private String contractId;
    private String contractNo;
    /** 回填：销售域合同变更版本 ID */
    private String contractChangeVerId;
    /** DRAFT|APPROVING|EXECUTING|DONE|VERIFIED|REJECTED */
    private String status;
    /** 审批实例 */
    private String apprId;
    /** 通过后写入基线版本号（V1.1） */
    private String verNoLabel;
    /** 第三档：客户书面确认附件已传（L1） */
    private String customerConfirmFlag;
    private String pmOpinion;
    /** PMO 核验说明 */
    private String verifyOpinion;
    /** 实际偏差%（>50 L4 升级 PMO） */
    private BigDecimal verifyDeviation;
}
