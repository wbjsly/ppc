package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 检验严格度记录（BR-4.12-02 / C-4.12-10，物料 + 供应商维度）：
 * 连续 2 批不合格或 SCAR 发出 → TIGHTENED（抽样上调一档）；
 * 加严下连续 3 批合格 → 解除；连续 5 批合格可申请放宽（质量经理审批）。
 */
@Getter
@Setter
@TableName("erp_qms_strictness")
public class Strictness extends BaseEntity {
    /** 物料编码 */
    @TableField("MATERIAL_CODE")
    private String materialCode;

    /** 供应商 ID */
    @TableField("SUPPLIER_ID")
    private String supplierId;

    /** NORMAL / TIGHTENED / RELAXED */
    @TableField("STRICTNESS")
    private String strictness;

    /** 2_FAIL / SCAR / 3_PASS / 5_PASS / MANUAL */
    @TableField("TRIGGER_TYPE")
    private String triggerType;

    /** 触发单据（SCAR/检验批 ID） */
    @TableField("TRIGGER_REF")
    private String triggerRef;

    /** ACTIVE / RELEASED */
    @TableField("STATUS")
    private String status;

    /** 生效时间 */
    @TableField("ACTIVE_DATE")
    private LocalDateTime activeDate;

    /** 解除依据（连续合格批次） */
    @TableField("RELEASE_BASIS")
    private String releaseBasis;

    /** 解除时间 */
    @TableField("RELEASE_DATE")
    private LocalDateTime releaseDate;

    /** 连续合格批次数 */
    @TableField("CONSEC_PASS")
    private Integer consecPass;

    /** 备注 */
    @TableField("REMARK")
    private String remark;

}
