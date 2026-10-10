package com.erp.service.mrp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 需求计划（change add-mrp-demand-planning，spec mrp-demand-planning，00-erp-spec 4.5-2 单期快照口径）。
 * 对应菜单 5.3.1 采购建议 / 5.3.2 生产建议 / 5.3.3 异常标记。
 * 角色：ROLE_PLANNER 运行/审核/转正/处置，写操作放行 ROLE_ADMIN；查询仅需认证。
 * 采购转正复用 mrp-auto-requisition 的 ProcMrpService.preview→generate（PR 单号与 Active 硬阻断以该能力为准）。
 */
public interface MrpPlanService {

    /** 转正占位计划工单前缀（proposal D6：5.4 工单管理落地时对接） */
    String PMO_PREFIX = "PMO-";

    /**
     * 触发正式 MRP 运行（手动 + 单运行互斥）：范围三选一 → 净算/展开/替代分配/倒排 →
     * 同事务取代旧活跃建议 + 批量落库；失败整体回滚并留 FAILED 运行记录。
     */
    Map<String, Object> run(String scopeType, String scopeValue);

    /** 运行历史（RUN_NO/范围/时间/统计） */
    List<Map<String, Object>> runs();

    /**
     * 建议列表（三菜单共用）：type=PURCHASE/PRODUCTION/EXCESS，status/runId/keyword 筛选；
     * exceptionsOnly=true 时聚合 EXCESS 与 OVERDUE（5.3.3）。
     */
    List<Map<String, Object>> suggestions(String type, String status, String runId,
                                          String keyword, boolean exceptionsOnly);

    /** 确认（可改量/日期，原值留痕）：PENDING→CONFIRMED，CAS */
    Map<String, Object> confirm(String id, BigDecimal confirmQty, LocalDate confirmDate);

    /** 取消（原因必填）：PENDING/CONFIRMED→CANCELLED，CAS */
    void cancel(String id, String reason);

    /** 采购转正：批量确认行 → preview 预检（任一行失败=整批 422）→ generate 出 PR → CONVERTED */
    Map<String, Object> convertPr(List<String> ids);

    /** 生产转正：批量确认行 → PMO-YYYYMMDD-NNN 占位单号（唯一冲突递增重试）→ CONVERTED */
    Map<String, Object> convertMo(List<String> ids);

    /** 异常处置：仅 EXCESS/OVERDUE 行，备注必填，处理人/时间留痕 */
    void handle(String id, String note);
}
