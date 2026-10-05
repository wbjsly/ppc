package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 标准适用范围：版本 × 物料/工序/供应商/客户 + 优先级；
 * 命中规则 = 优先级 + 生效日期唯一确定（同优先级取生效最新并记录依据）。
 */
@Getter
@Setter
@TableName("erp_qms_standard_applicability")
public class StandardApplicability extends BaseEntity {
    /** 版本 ID */
    @TableField("VERSION_ID")
    private String versionId;

    /** 物料 ID */
    @TableField("MATERIAL_ID")
    private String materialId;

    /** 物料编码 */
    @TableField("MATERIAL_CODE")
    private String materialCode;

    /** 品类编码 */
    @TableField("CATEGORY_CODE")
    private String categoryCode;

    /** 工序 ID（IPQC） */
    @TableField("PROCESS_ID")
    private String processId;

    /** 供应商（限定供方） */
    @TableField("SUPPLIER_ID")
    private String supplierId;

    /** 客户（OQC 限定） */
    @TableField("CUSTOMER_ID")
    private String customerId;

    /** 优先级，数值越小越具体 */
    @TableField("PRIORITY")
    private Integer priority;

}
