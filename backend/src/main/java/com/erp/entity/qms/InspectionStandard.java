package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 检验标准头（spec inspection-standard）：编码唯一且创建后不可改；
 * STATUS：DRAFT / ACTIVE / RETIRED（停用仅阻止新任务，BR-4.12-58）。
 */
@Getter
@Setter
@TableName("erp_qms_standard")
public class InspectionStandard extends BaseEntity {
    /** 标准编码（唯一，创建后不可改） */
    @TableField("STANDARD_CODE")
    private String standardCode;

    /** 标准名称 */
    @TableField("NAME")
    private String name;

    /** ITEM / CATEGORY / SUPPLIER / PROCESS / CUSTOMER */
    @TableField("SCOPE_TYPE")
    private String scopeType;

    /** 风险等级 A/B/C */
    @TableField("RISK_LEVEL")
    private String riskLevel;

    /** DRAFT / ACTIVE / RETIRED */
    @TableField("STATUS")
    private String status;

    /** 当前已发布版本号 */
    @TableField("CURRENT_VERSION")
    private Integer currentVersion;

    /** 标准负责人 */
    @TableField("OWNER_ID")
    private String ownerId;

    /** 负责人姓名 */
    @TableField("OWNER_NAME")
    private String ownerName;

    /** 说明 */
    @TableField("DESCRIPTION")
    private String description;

}
