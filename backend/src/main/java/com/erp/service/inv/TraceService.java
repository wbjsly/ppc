package com.erp.service.inv;

import java.util.List;
import java.util.Map;

/**
 * 批次追溯与召回（4.13，流程 8，spec trace-recall）。
 * 动作级分权（design D9）：发起/冻结执行/拦截登记/召回登记/结案 = 质量三角色
 * （结案再收 QUALITY_MGR/ADMIN）；查询与审计仅需认证。
 */
public interface TraceService {

    /** 审计动作（erp_inv_trace_log.ACTION） */
    String A_ANALYZE = "ANALYZE";
    String A_MATERIALIZE = "MATERIALIZE";
    String A_FREEZE = "FREEZE";
    String A_INTERCEPT = "INTERCEPT";
    String A_INTERCEPT_FAIL = "INTERCEPT_FAIL";
    String A_RETURN = "RETURN";
    String A_RECEIVE = "RECEIVE";
    String A_DISPOSE = "DISPOSE";
    String A_CLOSE = "CLOSE";

    /**
     * 追溯发起（FR-4.4-8-1，8.1）：三索引归一到批次（BATCH/SERIAL/SUPPLIER_BATCH），
     * 解析不到或歧义 422；同批次非 CLOSED 追溯单存在则 422 拒绝重复发起；
     * 生成 TR 单（ANALYZING）并同事务物化五类流向 → EXECUTING（FR-4.4-8-2）。
     */
    Map<String, Object> analyze(String indexType, String indexValue, String defectReason);

    /** 追溯单分页（状态/关键字筛选） */
    Map<String, Object> page(String status, String keyword, long current, long size);

    /** 追溯单详情：头 + 五类流向分组 + 各组状态汇总 */
    Map<String, Object> detail(String traceId);

    /**
     * 批量冻结执行（FR-4.4-8-3 / BR-4.4-49）：STOCK_AVAILABLE 行按批次走质量冻结链
     * （reason 关联 TR 单号）+ 同事务释放该批次 ACTIVE 预留；STOCK_FROZEN 行只关联
     * （LINKED）不重复冻；行置 FROZEN；每步写审计。
     */
    Map<String, Object> freezeStock(String traceId);

    /**
     * 在途拦截登记（FR-4.4-8-4）：success=true → 行 INTERCEPTED；
     * false → 原行 FAILED 终态 + 自动新增 OUT_SIGNED 召回清单行（应召=原在途数量）。
     */
    Map<String, Object> registerIntercept(String flowId, boolean success);

    /**
     * 召回登记（FR-4.4-8-5）：实退 → RECEIVED（应召量物化后不可改）；
     * 拒退 → REJECTED + 原因必填 + 通知质量主管。
     */
    Map<String, Object> registerReturn(String flowId, java.math.BigDecimal actualQty,
                                       String returnedBatch, String rejectReason);

    /**
     * 结案（8.7）：全部流向行终态校验（未闭环 422 展示清单）→ 召回率 = Σ实退/Σ应召
     * （<100% 逐笔未召回原因）→ 报告物化 REPORT_JSON → CLOSED；限 QUALITY_MGR/ADMIN。
     */
    Map<String, Object> close(String traceId);

    /** 审计查询（C-4.4-10）：按追溯单号时间序回放，仅需认证 */
    List<Map<String, Object>> auditLogs(String traceId);
}
