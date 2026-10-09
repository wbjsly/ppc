package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 检验标准版本：发布后只读（BR-4.12-57）、版本号连续、生效区间不重叠（C-4.12-18）；
 * APPROVAL_ID 关联通用审批底座（StandardPublish，CTQ 需双签）。
 */
@Getter
@Setter
@TableName("erp_qms_standard_version")
public class StandardVersion extends BaseEntity {
    /** 标准 ID */
    @TableField("STANDARD_ID")
    private String standardId;

    /** 版本号（连续，现行 +1） */
    @TableField("VERSION_NO")
    private Integer versionNo;

    /** 生效起 */
    @TableField("EFFECTIVE_FROM")
    private LocalDate effectiveFrom;

    /** 生效止 */
    @TableField("EFFECTIVE_TO")
    private LocalDate effectiveTo;

    /** 抽样方案 */
    @TableField("SAMPLING_PLAN_ID")
    private String samplingPlanId;

    /** AQL 等级（GB/T 2828.1 一般检验水平 II） */
    @TableField("AQL_LEVEL")
    private String aqlLevel;

    /** 检验水平 */
    @TableField("INSPECTION_LEVEL")
    private String inspectionLevel;

    /** DRAFT / PENDING_APPROVE / RELEASED / RETIRED */
    @TableField("STATUS")
    private String status;

    /** 发布审批实例 ID */
    @TableField("APPROVAL_ID")
    private String approvalId;

    /** 发布人 */
    @TableField("RELEASED_BY")
    private String releasedBy;

    /** 发布时间 */
    @TableField("RELEASED_DATE")
    private LocalDateTime releasedDate;

    /** 变更原因（新版本必填） */
    @TableField("CHANGE_REASON")
    private String changeReason;

}
