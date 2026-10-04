package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 价格协议行（表 erp_mdm_price_agreement_line）。
 * LADDER：MIN_QTY/MAX_QTY 闭区间（同协议同 SKU 不重叠）；EXCLUSIVE/TIME：仅单价，数量区间空。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_mdm_price_agreement_line")
public class MdmPriceAgreementLine extends BaseEntity {

    private String paId;

    /** SKU（参照启用物料 erp_mdm_item.ITEM_CODE） */
    private String itemCode;

    private BigDecimal unitPrice;

    /** 阶梯下限（含，闭区间） */
    private BigDecimal minQty;

    /** 阶梯上限（含，闭区间） */
    private BigDecimal maxQty;
}
