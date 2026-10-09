package com.erp.entity.system;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 审批实例（通用审批底座，change add-quality-collaboration design D4）。
 * 状态机：PENDING → APPROVED | REJECTED | CANCELLED。
 * BIZ_TYPE + BIZ_ID 指向一笔业务；同一业务存在 PENDING 实例时重复提交由服务层 422。
 * 偏差 D6：与既有 PR/PO/招标审批双轨并存，旧审批不迁移。
 */
@Getter
@Setter
@TableName("erp_sys_approval")
public class ApprovalInstance extends BaseEntity {
    /** AP + yyyyMMdd + - + 6 位流水 */
    @TableField("APPR_NO")
    private String apprNo;

    /** 业务类型（StandardPublish/Exempt/Concession/NcrDisposition/Capa/Scar/CopqFinance/Return） */
    @TableField("BIZ_TYPE")
    private String bizType;

    /** 业务单据 ID */
    @TableField("BIZ_ID")
    private String bizId;

    /** 审批标题 */
    @TableField("TITLE")
    private String title;

    /** PENDING / APPROVED / REJECTED / CANCELLED */
    @TableField("STATUS")
    private String status;

    /** 超时升级目标角色 */
    @TableField("ESCALATE_TO")
    private String escalateTo;

    /** 发起人 */
    @TableField("APPLY_BY")
    private String applyBy;

    /** 发起时间 */
    @TableField("APPLY_DATE")
    private LocalDateTime applyDate;

    /** 办结时间 */
    @TableField("FINISH_DATE")
    private LocalDateTime finishDate;

    /** 备注 */
    @TableField("REMARK")
    private String remark;

}
