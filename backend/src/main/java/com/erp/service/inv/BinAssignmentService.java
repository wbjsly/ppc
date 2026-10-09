package com.erp.service.inv;

import java.util.List;
import java.util.Map;

/**
 * 仓位分配（4.4.5，spec bin-assignment）：
 * 推荐器（类型分流 → 三属性合规 → 托位容量过滤，三级排序 Top3 带理由）+ 分配台账状态机
 * + 待过账强前置数据源（Tab A）+ 未分配补上架（Tab B）。
 * 写操作限 ROLE_WAREHOUSE / ROLE_ADMIN（服务层强制）；查询仅需认证。
 */
public interface BinAssignmentService {

    /** Tab A：待分配 GR 行分页（含每行分配状态与已确认量聚合） */
    Map<String, Object> pendingGrLines(String keyword, long current, long size);

    /** 查看建议：该 GR 行的 Top3 候选（不落库；不合格行 422） */
    List<Map<String, Object>> candidates(String grNo, Integer lineNo);

    /**
     * 推荐：合格候选 → 生成 RECOMMENDED 记录（Top1）；无候选 → SUSPENDED 挂起记录
     * + 通知仓库主管（C-4.4-11）。
     * 返回 {record, candidates, suspended}。
     */
    Map<String, Object> recommend(String grNo, Integer lineNo);

    /**
     * 一键推荐全部并确认：逐行 推荐→CONFIRMED（异常行挂起不阻断）。
     * 返回 {confirmed, suspended, total}。
     */
    Map<String, Object> recommendAll();

    /** 确认分配：RECOMMENDED → CONFIRMED（STOCK 来源则同时执行上架移位） */
    void confirm(String putawayId);

    /** 指定/改派：候选合规复检（不豁免）→ 生成新 CONFIRMED 记录替代旧记录（留痕） */
    void assign(String grNo, Integer lineNo, String binCode);

    /** Tab B：未分配（BIN_CODE=''）库存行分页 */
    Map<String, Object> unassignedStock(String keyword, long current, long size);

    /** Tab B：某未分配行的上架候选 Top3（不落库） */
    List<Map<String, Object>> unassignedCandidates(String stockId);

    /** Tab B：推荐上架（RECOMMENDED；无候选挂起+通知） */
    Map<String, Object> recommendPutaway(String stockId);

    /** 台账历史（某来源单的全部记录，含被替代记录） */
    List<Map<String, Object>> history(String sourceDocNo);
}
