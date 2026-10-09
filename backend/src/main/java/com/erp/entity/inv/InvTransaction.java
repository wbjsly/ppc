package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 出入库流水（spec stock-posting-engine，迁移 104，纯追加无 update 路径）。
 * 行级一维一条（FIFO 拆批多条同来源单号）；数量恒正、方向区分加减；
 * 冻结平移与预留消耗不进流水。BR-4.4-13 事件日志/BR-4.11-15 历史流水/BR-4.4-48 追溯索引。
 */
@Getter
@Setter
@TableName("erp_inv_transaction")
public class InvTransaction extends BaseEntity {

    public static final String DIR_IN = "IN";
    public static final String DIR_OUT = "OUT";

    private String txnNo;
    private String direction;
    private String typeCode;
    private String bizDocType;
    private String bizDocNo;
    private String warehouseCode;
    private String itemCode;
    private String batchNo;
    /** 仓位编号（位级一维一条；空串=未分配位，change add-bin-assignment，迁移 105） */
    private String binCode;
    private BigDecimal qty;
    /** 该维度在手（QTY）前/后余额 —— 对账主链 */
    private BigDecimal beforeQty;
    private BigDecimal afterQty;
    private String remark;
}
