package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 外协对账单行（change add-outsourcing-processing，spec outsourcing-settlement，迁移 126）：
 * 一行引用一条收货过账行（GR_LINE_ID 唯一键 = 重复引用的 schema 级防线）；
 * QTY = 收货过账量，UNIT_PRICE = PO 行成交价快照，AMOUNT = QTY × UNIT_PRICE。
 */
@Getter
@Setter
@TableName("erp_proc_os_settlement_line")
public class OsSettlementLine extends BaseEntity {

    private String osrId;
    private Integer lineNo;
    /** 收货过账行（唯一引用锚点） */
    private String grLineId;
    /** 收货单号（冗余展示） */
    private String grNo;
    private String itemCode;
    private String itemName;
    /** 本次对账数量 = 收货过账量 */
    private BigDecimal qty;
    /** PO 行单价快照 */
    private BigDecimal unitPrice;
    /** 金额小计 = QTY × UNIT_PRICE */
    private BigDecimal amount;
}
