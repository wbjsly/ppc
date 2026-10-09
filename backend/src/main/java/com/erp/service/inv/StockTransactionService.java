package com.erp.service.inv;

import com.erp.entity.inv.InvTransaction;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 出入库流水查询（spec stock-posting-engine，纯读）。
 * 按维度/来源单据/类型码/方向/时间筛选；方向合计不含方向过滤（对账口径）。
 */
public interface StockTransactionService {

    /**
     * 分页查询 + 方向合计。
     * 过滤：bizDocType/bizDocNo/typeCode/direction/itemCode/warehouseCode/batchNo/binCode/from/to/keyword。
     * 返回 {rows, total, sumIn, sumOut, asOf}；合计基于同过滤但忽略 direction（IN/OUT 双向对账）。
     */
    Map<String, Object> page(long current, long size, String bizDocType, String bizDocNo,
                             String typeCode, String direction, String itemCode,
                             String warehouseCode, String batchNo, String binCode,
                             LocalDateTime from, LocalDateTime to, String keyword);

    /** 来源单据流水（作业台详情抽屉按 BIZ_DOC_TYPE+BIZ_DOC_NO 下钻） */
    List<InvTransaction> byBizDoc(String bizDocType, String bizDocNo);
}
