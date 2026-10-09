package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 追溯流向行（4.13，spec trace-recall 五类流向；迁移 115）。
 * 行级动作状态承载各流向的独立推进；OUT_SIGNED 行即召回清单（应召量物化定格）。
 */
@Getter
@Setter
@TableName("erp_inv_trace_flow")
public class TraceFlow extends BaseEntity {

    /** 五类流向（BR-4.4-48）：在库可用 */
    public static final String F_STOCK_AVAILABLE = "STOCK_AVAILABLE";
    /** 在库已冻结 */
    public static final String F_STOCK_FROZEN = "STOCK_FROZEN";
    /** 调拨在途 */
    public static final String F_IN_TRANSIT = "IN_TRANSIT";
    /** 已出库未签收 */
    public static final String F_OUT_UNSIGNED = "OUT_UNSIGNED";
    /** 已签收（召回清单） */
    public static final String F_OUT_SIGNED = "OUT_SIGNED";

    /** 未处置 */
    public static final String ST_PENDING = "PENDING";
    /** 已冻结（STOCK_AVAILABLE 批量冻结后） */
    public static final String ST_FROZEN = "FROZEN";
    /** 已关联（STOCK_FROZEN 只关联追溯单号不重复冻） */
    public static final String ST_LINKED = "LINKED";
    /** 拦截成功（终态） */
    public static final String ST_INTERCEPTED = "INTERCEPTED";
    /** 拦截失败（留痕终态，同时升级出新召回行） */
    public static final String ST_FAILED = "FAILED";
    /** 实退登记完成 */
    public static final String ST_RECEIVED = "RECEIVED";
    /** 客户拒退（留痕终态） */
    public static final String ST_REJECTED = "REJECTED";
    /** 处置完成（FROZEN/RECEIVED → 报废后终态） */
    public static final String ST_DISPOSED = "DISPOSED";

    /** 来源单据类型：调拨 */
    public static final String SRC_TRANSFER = "TRANSFER";
    /** 来源单据类型：发运 */
    public static final String SRC_SHIPMENT = "SHIPMENT";

    private String traceId;

    /** STOCK_AVAILABLE / STOCK_FROZEN / IN_TRANSIT / OUT_UNSIGNED / OUT_SIGNED */
    private String flowType;

    private String status;

    private String warehouseCode;

    private String binCode;

    private String itemCode;

    private String itemName;

    private String batchNo;

    /** 流向数量（在途/发运数量；库存类为位行量） */
    private BigDecimal qty;

    /** 应召数量（物化时定格，客户变更不改基数 FR-4.4-8-5） */
    private BigDecimal expectQty;

    /** 实退数量 */
    private BigDecimal actualQty;

    /** 退回批次 */
    private String returnedBatch;

    /** 拒退/拦截失败原因（拒退必填） */
    private String rejectReason;

    /** TRANSFER / SHIPMENT */
    private String srcDocType;

    /** 调拨单号 / 发运单号 */
    private String srcDocNo;

    private String customerCode;

    private String customerName;

    /** 签收时间（OUT_SIGNED） */
    private LocalDateTime signAt;

    /** 1=待人工核查（发运行批次缺失但物料命中疑似流向） */
    private String checkFlag;

    private String note;
}
