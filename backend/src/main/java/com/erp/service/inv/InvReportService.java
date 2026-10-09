package com.erp.service.inv;

import java.util.List;
import java.util.Map;

/**
 * 库存报表（4.14，spec inventory-reports）：实时位行查询、库存周转（M-WMS-001 口径 + 日结快照
 * 分母降级与补数）、库龄分桶与呆滞识别、呆滞每日推送、导出行数据源（双路径导出共用）。
 * 只读查询仅需认证；菜单 PERM 管可见性（design D1 单服务 + ReportController）。
 */
public interface InvReportService {

    /** 周转分子口径：销售成本（SALES_OUT，M-WMS-001 字典口径） */
    String SCOPE_SALES = "SALES_OUT";
    /** 周转分子口径：全部出库（页面标注非指标字典口径） */
    String SCOPE_ALL_OUT = "ALL_OUT";

    // ---------- 4.14.1 实时查询（spec 实时库存位行查询） ----------

    /**
     * 位行明细分页：仓库/物料/批次/关键字/ABC 组合筛选，库龄 = 今天 − INBOUND_DATE（NULL → 未知），
     * 含批次合计；默认库龄降序（NULL 靠后）。
     */
    Map<String, Object> realtime(String warehouseCode, String itemCode, String batchNo,
                                 String keyword, String abcClass, long current, long size);

    // ---------- 4.14.2 周转分析（spec 库存周转计算口径 / 汇总与趋势） ----------

    /**
     * 期间周转指标：分子 = 期间 OUT 流水 × STANDARD_COST（scope 口径）；分母 = 期间日结快照
     * 逐日库存金额均值；快照天数 < 1 → degraded=true 分母降级为当前库存金额；
     * 分母 0 → rate/turnoverDays=null（「不适用」）。
     */
    Map<String, Object> turnover(String from, String to, String scope);

    /** 汇总表：groupBy = ITEM / WAREHOUSE / ABC（ABC 空值归「未分类」） */
    List<Map<String, Object>> turnoverSummary(String from, String to, String scope, String groupBy);

    /** 跨月趋势：近 months 个月逐月 {period, rate, days, degraded}；缺快照月份不输出点（前端断开） */
    List<Map<String, Object>> turnoverTrend(int months);

    /**
     * 补数（spec：幂等回补最近一个缺失日的日结快照）——委托既有 runDayClose（补昨天、按日幂等）。
     */
    Map<String, Object> backfillDayClose();

    // ---------- 5.1/5.2 库龄分析（spec 库龄分析与呆滞识别） ----------

    /** 库龄五桶 + 未知桶分布（行数与金额 QTY×STANDARD_COST）+ 呆滞金额/占比（阈值 SLOW_MOVING_AGE_DAYS） */
    Map<String, Object> agingBuckets(String warehouseCode, String itemCode, String abcClass);

    /** 库龄明细（staleOnly=true 仅呆滞行），库龄降序分页 */
    Map<String, Object> agingList(String warehouseCode, String itemCode, String abcClass,
                                  boolean staleOnly, long current, long size);

    // ---------- 6.1 呆滞每日推送 ----------

    /** 扫描库龄超阈值物料 → 通知 ROLE_WAREHOUSE（幂等键=物料+日期）；异常吞掉只记日志。返回推送数 */
    int dailySlowMovingScan();

    // ---------- 7.1 双路径导出数据源 ----------

    /**
     * 导出行数据：type = realtime / turnover / aging；返回 {headers:[...], rows:[[...]]}
     * （同步 CSV 端点与 bi ExportService inv-report dataset 共用）。
     */
    Map<String, Object> exportRows(String type, Map<String, Object> params);

    // ---------- 内部共享（汇总/明细复用） ----------

    /** 当前库存金额（呆滞占比分母、降级分母共用口径：QTY × STANDARD_COST） */
    Map<String, Object> currentStockValue(String warehouseCode, String itemCode, String abcClass);
}
