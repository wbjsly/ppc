package com.erp.entity.eam;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * CIP 成本归集明细（add-asset-capitalization，BR-4.13-08）：
 * 类别限工程款/安装费/资本化利息三类。
 */
@Getter
@Setter
@TableName("erp_eam_cip_cost")
public class EamCipCost extends BaseEntity {

    @TableField("PROJECT_ID")
    private String projectId;

    /** ENGINGEE 工程款 / INSTALL 安装费 / INTEREST 资本化利息 */
    @TableField("COST_TYPE")
    private String costType;

    @TableField("AMOUNT")
    private BigDecimal amount;

    @TableField("COST_DATE")
    private LocalDate costDate;

    /** 来源单号（业财锚点，后续挂应付结算） */
    @TableField("SOURCE_DOC_NO")
    private String sourceDocNo;

    @TableField("DESCRIPTION")
    private String description;
}
