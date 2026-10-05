package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * CAPA/8D（spec capa-management）：CURRENT_STEP = D1~D8 不可跳序；
 * D7 有效性验证非「有效」阻断关联 NCR 关闭（BR-4.12-35 / C-4.12-05）；
 * 关闭 1 年内复发自动新建并经 ORIGIN_CAPA_ID 关联、严重度上调（BR-4.12-36）。
 */
@Getter
@Setter
@TableName("erp_qms_capa")
public class Capa extends BaseEntity {
    /** CA + yyyyMMdd + - + 6 位流水 */
    @TableField("CAPA_NO")
    private String capaNo;

    /** 关联 NCR */
    @TableField("NCR_ID")
    private String ncrId;

    /** NCR 号 */
    @TableField("NCR_NO")
    private String ncrNo;

    /** REPEAT / EVENT / COMPLAINT / AUDIT / MANUAL */
    @TableField("SOURCE_TYPE")
    private String sourceType;

    /** 问题标题 */
    @TableField("TITLE")
    private String title;

    /** 问题描述（5W2H） */
    @TableField("PROBLEM_DESC")
    private String problemDesc;

    /** 影响范围 */
    @TableField("SCOPE_DESC")
    private String scopeDesc;

    /** CRITICAL / MAJOR / MINOR */
    @TableField("SEVERITY")
    private String severity;

    /** CAPA 负责人 */
    @TableField("OWNER_ID")
    private String ownerId;

    /** 负责人姓名 */
    @TableField("OWNER_NAME")
    private String ownerName;

    /** 当前 8D 步骤 D1~D8 */
    @TableField("CURRENT_STEP")
    private String currentStep;

    /** OPEN/ANALYZING/VERIFYING/REANALYZING/CLOSED */
    @TableField("STATUS")
    private String status;

    /** Critical 24h 遏制倒计时 */
    @TableField("CONTAIN_DUE_TIME")
    private LocalDateTime containDueTime;

    /** 1=遏制已确认 */
    @TableField("CONTAIN_CONFIRMED")
    private String containConfirmed;

    /** 1=遏制超时升级 */
    @TableField("CONTAIN_ESCALATED")
    private String containEscalated;

    /** 根本原因（D4） */
    @TableField("ROOT_CAUSE")
    private String rootCause;

    /** 数据/实验验证证据 */
    @TableField("ROOT_CAUSE_EVIDENCE")
    private String rootCauseEvidence;

    /** PENDING/VALID/PARTIAL/INVALID */
    @TableField("VERIFY_RESULT")
    private String verifyResult;

    /** 验证指标 */
    @TableField("VERIFY_INDICATOR")
    private String verifyIndicator;

    /** 验证目标 */
    @TableField("VERIFY_TARGET")
    private String verifyTarget;

    /** 验证数据 */
    @TableField("VERIFY_DATA")
    private String verifyData;

    /** 复发关联的原 CAPA */
    @TableField("ORIGIN_CAPA_ID")
    private String originCapaId;

    /** 立项时限（评审后 5 工作日） */
    @TableField("DUE_DATE")
    private LocalDate dueDate;

    /** 关闭时间 */
    @TableField("CLOSED_DATE")
    private LocalDateTime closedDate;

    /** 备注 */
    @TableField("REMARK")
    private String remark;

}
