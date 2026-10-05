package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 免检（BR-4.12-12）：物料 + 供应商维度，须质量经理审批生效；
 * 任一批次不合格自动停用并恢复按风险等级抽样。
 */
@Getter
@Setter
@TableName("erp_qms_exempt")
public class Exempt extends BaseEntity {
    /** 物料 ID */
    @TableField("MATERIAL_ID")
    private String materialId;

    /** 物料编码 */
    @TableField("MATERIAL_CODE")
    private String materialCode;

    /** 物料名称 */
    @TableField("MATERIAL_NAME")
    private String materialName;

    /** 供应商 ID（空 = 不限供方） */
    @TableField("SUPPLIER_ID")
    private String supplierId;

    /** 供应商名称 */
    @TableField("SUPPLIER_NAME")
    private String supplierName;

    /** PENDING / ACTIVE / DISABLED */
    @TableField("STATUS")
    private String status;

    /** 免检审批实例 ID */
    @TableField("APPROVAL_ID")
    private String approvalId;

    /** 生效操作人 */
    @TableField("ENABLED_BY")
    private String enabledBy;

    /** 生效时间 */
    @TableField("ENABLED_DATE")
    private LocalDateTime enabledDate;

    /** 停用原因（不合格自动取消） */
    @TableField("DISABLE_REASON")
    private String disableReason;

    /** 停用时间 */
    @TableField("DISABLED_DATE")
    private LocalDateTime disabledDate;

    /** 备注 */
    @TableField("REMARK")
    private String remark;

}
