package com.erp.entity.vmi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** 领料单行（FIFO 配批结果固化，spec material-issue）。 */
@Getter
@Setter
@TableName("erp_inv_material_issue_line")
public class MaterialIssueLine extends BaseEntity {

    private String issueId;
    private Integer lineNo;
    private String itemCode;
    private String itemName;
    private String batchNo;

    /** 拣货推荐回写仓位（4.6.3；空=过账时引擎位级 FIFO 分配） */
    private String binCode;
    /** 本批领用数量 */
    private BigDecimal qty;
    /** OWN / VMI（头冗余便于检索） */
    private String stockType;
    /** 寄售供应商（VMI 行） */
    private String supplierId;
    /** 领用时点协议价（VMI 行，物权转移计价） */
    private BigDecimal unitPrice;
    /** 金额 = QTY × UNIT_PRICE（VMI 行） */
    private BigDecimal amount;
}
