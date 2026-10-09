package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 追溯单头（4.13 批次追溯，spec trace-recall；迁移 115）。
 * 三索引归一到批次（FR-4.4-8-1）；状态机 ANALYZING → EXECUTING → CLOSED。
 */
@Getter
@Setter
@TableName("erp_inv_trace_order")
public class TraceOrder extends BaseEntity {

    /** 索引类型：批次号 */
    public static final String IDX_BATCH = "BATCH";
    /** 索引类型：序列号（经 erp_inv_serial 反查批次） */
    public static final String IDX_SERIAL = "SERIAL";
    /** 索引类型：供应商批次号（经 SUPPLIER_BATCH_NO 查找） */
    public static final String IDX_SUPPLIER_BATCH = "SUPPLIER_BATCH";

    /** 分析中（已发起、流向未物化） */
    public static final String ST_ANALYZING = "ANALYZING";
    /** 执行中（流向物化完成，等待冻结/拦截/召回/处置闭环） */
    public static final String ST_EXECUTING = "EXECUTING";
    /** 已结案（全部流向行终态 + 结案报告物化） */
    public static final String ST_CLOSED = "CLOSED";

    /** 追溯单号 TR+yyMMdd+4位日流水 */
    private String traceNo;

    /** BATCH / SERIAL / SUPPLIER_BATCH */
    private String indexType;

    /** 录入的索引值 */
    private String indexValue;

    /** 归一解析出的批次号（重复发起拦截键） */
    private String batchNo;

    private String itemCode;

    private String itemName;

    /** 缺陷描述（发起必填） */
    private String defectReason;

    private String status;

    /** 双段：①五类流向快照 ②结案报告（召回率+未召回明细） */
    private String reportJson;

    private String closeBy;

    private LocalDateTime closeAt;
}
