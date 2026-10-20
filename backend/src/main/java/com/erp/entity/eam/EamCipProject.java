package com.erp.entity.eam;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 在建工程（CIP）项目台账（add-asset-capitalization，design D2）：
 * 归集成本 vs 已结算金额差额供 BR-4.13-08 部分转资检查；
 * 完工验收日期为转资超期预警数据源（BR-4.13-06）。
 */
@Getter
@Setter
@TableName("erp_eam_cip_project")
public class EamCipProject extends BaseEntity {

    /** CIP + yyyyMMdd + - + 4 位流水 */
    @TableField("PROJECT_NO")
    private String projectNo;

    @TableField("NAME")
    private String name;

    /** 预算金额 */
    @TableField("BUDGET_AMOUNT")
    private BigDecimal budgetAmount;

    /** 已结算金额（BR-4.13-08 差额检查） */
    @TableField("SETTLED_AMOUNT")
    private BigDecimal settledAmount;

    /** OPEN / COMPLETED / CLOSED */
    @TableField("STATUS")
    private String status;

    /** 完工验收日期（超期预警基准日） */
    @TableField("COMPLETION_DATE")
    private LocalDate completionDate;

    @TableField("OWNER_NAME")
    private String ownerName;

    @TableField("REMARK")
    private String remark;
}
