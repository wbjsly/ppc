package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 结项检查单头（add-project-closure，S-4.8-07 / C-4.8-05 审批依据留档）。 */
@Getter
@Setter
@TableName("erp_pm_closure_check")
public class ClosureCheck extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_FROZEN = "FROZEN";

    /** 项目 erp_pm_project.ID */
    private String projectId;
    /** 冗余项目编号（归档可查） */
    private String projectNo;
    /** DRAFT 起草/可改 | FROZEN 已提交审批只读 */
    private String status;
    /** 有条件结项标记（PMO 有条件通过=1） */
    private String conditionalFlag;
    /** 审批实例（ApprovalEngine） */
    private String approvalId;
    /** 资源释放清单快照 JSON（归档时生成：参与人/工时汇总） */
    private String resReleaseJson;
    private String submitBy;
    private LocalDateTime submitDate;
    /** PMO 审批意见（有条件通过说明） */
    private String auditOpinion;
}
