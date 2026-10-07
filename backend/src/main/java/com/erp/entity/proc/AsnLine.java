package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** ASN 行（spec asn-collaboration）。 */
@Getter
@Setter
@TableName("erp_proc_asn_line")
public class AsnLine extends BaseEntity {

    public static final String ST_OPEN = "OPEN";
    public static final String ST_CLOSED = "CLOSED";

    private String asnId;
    private String poLineId;
    private Integer lineNo;
    private String itemCode;
    private String itemName;
    private String unit;
    /** 发货数量（含容差内与超容差合计） */
    private BigDecimal qty;
    /** 本行超容差数量 */
    private BigDecimal overQty;
    /** 已核销收货数量 */
    private BigDecimal receivedQty;
    private String batchNo;
    /** OPEN / CLOSED */
    private String lineStatus;
}
