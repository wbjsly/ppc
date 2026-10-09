package com.erp.service.inv;

import java.util.List;
import java.util.Map;

/**
 * 库存三态快照查询（spec stock-snapshot，FR-4.4-2-2 / BR-4.4-14）。
 * 可用口径 = 在手(QTY) − 冻结(QC+FIN) − 预留(ACTIVE 汇总)，查询时实时计算，
 * 不读 AVAILABLE_QTY 列（design D3：该列不扣预留，读列会与 ATP 口径打架）。
 * VMI 寄售在独立表，天然不计入（BR-4.4-14 单独核算）。
 */
public interface StockSnapshotService {

    /** 可用库存查询：rows + totals + asOf + 每行 pendingVerify（最近一次校验差异标记） */
    Map<String, Object> query(String warehouseCode, String itemCode, String batchNo, String keyword);

    /** 预留下钻：该维度全部 ACTIVE 预留（SO 单号/行号/锁定时间/数量） */
    List<Map<String, Object>> reservedDetail(String warehouseCode, String itemCode, String batchNo);

    /** 冻结下钻：该维度生效中冻结台账逐条（类型/原因/状态/来源） */
    List<Map<String, Object>> freezeDetail(String warehouseCode, String itemCode, String batchNo);

    /** 最近一次恒等式校验报告（4.3.1 待核实标记来源） */
    Map<String, Object> latestCheckReport();

    /** 在制库存查询：工单域（5.4）未建，恒空（偏差 D5，接口契约就位） */
    Map<String, Object> wipQuery();

    /**
     * 每日恒等式校验（FR-4.4-2-5 / BR-4.4-15，01:30 调度触发，按运行日幂等）：
     * 逐行断言 QTY = AVAILABLE + QC + FIN，差异写报告 + 通知仓库主管，一致写零差异留痕。
     *
     * @return {runDate, status, mismatchCnt, skipped}
     */
    Map<String, Object> runCheckReport();

    /**
     * 库存日结（FR-4.4-2-6，00:05 调度触发）：为昨日生成余额快照，插入即终态；
     * 当日已存在 → 跳过（幂等）；单事务全有或全无。
     *
     * @return {balDate, inserted, skipped}
     */
    Map<String, Object> runDayClose();
}
