package com.erp.entity.vmi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** VMI 协议行：寄售物料清单 + 最高/最低水位 + 价格条款（FR-4.2-8-1）。 */
@Getter
@Setter
@TableName("erp_proc_vmi_agreement_line")
public class VmiAgreementLine extends BaseEntity {

    private String agreeId;
    private String itemCode;
    private String itemName;
    private String unit;
    /** 最低库存水位（低于生成补货建议） */
    private BigDecimal minQty;
    /** 最高库存水位（超出阻断收货 BR-4.2-36；0=不限） */
    private BigDecimal maxQty;
    /** 协议单价（领用计价基准） */
    private BigDecimal unitPrice;
    /** 价格条款起（领用日须在条款内，C-4.2-09） */
    private LocalDate priceStart;
    /** 价格条款止 */
    private LocalDate priceEnd;
}
