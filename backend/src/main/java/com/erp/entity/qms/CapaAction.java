package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * CAPA 措施（纠正 CORRECT / 预防 PREVENT）：
 * 涉及标准/SOP 变更须走变更审批（BR-4.12-34）；存在未完成措施阻断进入 D7。
 */
@Getter
@Setter
@TableName("erp_qms_capa_action")
public class CapaAction extends BaseEntity {
    /** CAPA ID */
    @TableField("CAPA_ID")
    private String capaId;

    /** CORRECT / PREVENT */
    @TableField("ACTION_TYPE")
    private String actionType;

    /** 措施内容 */
    @TableField("DESCRIPTION")
    private String description;

    /** 负责人 */
    @TableField("OWNER_ID")
    private String ownerId;

    /** 负责人姓名 */
    @TableField("OWNER_NAME")
    private String ownerName;

    /** 计划完成日 */
    @TableField("DUE_DATE")
    private LocalDate dueDate;

    /** PENDING / DONE / OVERDUE */
    @TableField("STATUS")
    private String status;

    /** 预期/实际效果 */
    @TableField("EFFECT_DESC")
    private String effectDesc;

    /** 1=涉及标准/SOP 变更 */
    @TableField("STANDARD_CHANGE_FLAG")
    private String standardChangeFlag;

    /** 变更审批实例 */
    @TableField("CHANGE_APPROVAL_ID")
    private String changeApprovalId;

    /** 完成时间 */
    @TableField("DONE_TIME")
    private LocalDateTime doneTime;

}
