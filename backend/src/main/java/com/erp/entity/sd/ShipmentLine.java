package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 销售发货单行（tasks 9.1，spec sales-shipment）。
 * 行级保留来源 SO 行引用（合并发货多 SO 场景）；BATCH_ALLOC 存过账 FIFO 选批结果 JSON。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_shipment_line")
public class ShipmentLine extends BaseEntity {

    public static final String LS_PENDING = "PENDING";
    public static final String LS_POSTED = "POSTED";
    public static final String LS_CANCELLED = "CANCELLED";

    private String shipId;
    private Integer lineNo;
    private String soId;
    private String soNo;
    private String soLineId;
    private Integer soLineNo;
    private String itemCode;
    private String itemName;
    /** 发货数量（≤ 未发余量与可锁预留量，9.5） */
    private BigDecimal qty;
    private String baseUnit;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    private String warehouseCode;

    /** 拣货推荐回写批次（4.6.3；空=过账时按 FIFO 选批） */
    private String batchNo;

    /** 拣货推荐回写仓位（4.6.3；空=过账时引擎位级 FIFO 分配） */
    private String binCode;
    /** 分批交期（9.4 行级带出） */
    private LocalDate planShipDate;
    private String lineStatus;
    /** 过账 FIFO 选批结果 JSON：[{batchNo, qty}] */
    private String batchAlloc;
    private LocalDateTime postAt;
    private String remark;
}
