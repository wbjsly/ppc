package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 跨法人内部发票台账（F3/I2，spec internal-transfer-accounting）。
 * 独立台账不进销售开票域（erp_sd_invoice_apply 的 shipId 锚点不受调拨污染）；
 * OUT=内部销售票（调出方视角）/ IN=内部采购票（调入方视角），按调拨单号 1:1 配对核销。
 * 桩口径不开真票（偏差 D2）。
 */
@Getter
@Setter
@TableName("erp_fin_internal_invoice")
public class FinInternalInvoice extends BaseEntity {

    public static final String DIR_OUT = "OUT";
    public static final String DIR_IN = "IN";
    public static final String ST_ISSUED = "ISSUED";
    public static final String ST_SETTLED = "SETTLED";

    /** IT+yyyyMMdd+流水（桩口径自生成） */
    private String intInvNo;
    private String direction;
    private String transferNo;
    private String outLeCode;
    private String inLeCode;
    private String issuerLeCode;
    private BigDecimal netAmount;
    private BigDecimal taxRate;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    /** 配对票 ID（入库过账自动核销时回填） */
    private String pairId;
    private String status;
    private LocalDateTime settledAt;
    private String remark;
}
