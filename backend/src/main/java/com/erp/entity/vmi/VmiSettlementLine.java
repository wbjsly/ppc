package com.erp.entity.vmi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** VMI 结算单行（引用领料行，供应商异议对账视图）。 */
@Getter
@Setter
@TableName("erp_proc_vmi_settlement_line")
public class VmiSettlementLine extends BaseEntity {

    private String settleId;
    private Integer lineNo;
    private String issueId;
    /** 领料行 ID（唯一引用，一结算单一发票 design D8） */
    private String issueLineId;
    private String issueNo;
    private String workOrderNo;
    private String itemCode;
    private String itemName;
    private String batchNo;
    private BigDecimal qty;
    /** 领用时点协议价 */
    private BigDecimal unitPrice;
    private BigDecimal amount;
}
