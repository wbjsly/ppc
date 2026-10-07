package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** 成本日聚合快照（design D2：单快照行双金额列）。 */
@Getter
@Setter
@TableName("erp_bi_cost_snapshot")
public class BiCostSnapshot extends BaseEntity {

    private String batchNo;
    private String monthTag;
    private String legalEntityId;
    private String categoryCode;
    private String itemCode;
    private String supplierId;
    private String buyer;
    private BigDecimal poQty;
    private BigDecimal poAmt;
    private BigDecimal poAmtNoTax;
    private BigDecimal taxAmt;
    private BigDecimal freightAmt;
    private BigDecimal returnAmt;
    private BigDecimal invQty;
    private BigDecimal invAmt;
    private BigDecimal invAmtNoTax;
    private BigDecimal invTax;
    private BigDecimal diffAmt;
    private Boolean estimating;
    private String remark;
}
