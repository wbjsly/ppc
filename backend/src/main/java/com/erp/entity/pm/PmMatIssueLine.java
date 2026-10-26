package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 项目领料明细行（add-cost-collection-freeze）。 */
@Getter
@Setter
@TableName("erp_pm_mat_issue_line")
public class PmMatIssueLine extends BaseEntity {

    private String issueId;
    /** 任务级（BR-4.8-20 必填） */
    private String wbsId;
    /** 物料编码 */
    private String itemId;
    private String itemName;
    private String batchNo;
    /** 扣库存仓库 */
    private String warehouseCode;
    private java.math.BigDecimal qty;
    /** 单价快照（库存无成本列，由录入携带） */
    private java.math.BigDecimal unitPrice;
    /** =QTY×UNIT_PRICE */
    private java.math.BigDecimal amount;
}
