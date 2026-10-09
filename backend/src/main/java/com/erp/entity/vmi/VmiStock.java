package com.erp.entity.vmi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 寄售库存（物权=供应商，独立分账，BR-4.2-01 / design D1）。唯一键：物料+批次+供应商。 */
@Getter
@Setter
@TableName("erp_inv_vmi_stock")
public class VmiStock extends BaseEntity {

    private String itemCode;
    private String itemName;
    private String batchNo;
    private String supplierId;
    private String supplierName;
    /** 寄售现有量 */
    private BigDecimal qty;
    /** 累计量领用（账龄处置判断 BR-4.2-39） */
    private BigDecimal issuedQty;
    /** 首次入库日期（FIFO 依据，补货不刷新） */
    private LocalDate inboundDate;
    /** 最近入库关联协议号 */
    private String agreeNo;
}
