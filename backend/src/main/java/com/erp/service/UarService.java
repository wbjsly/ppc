package com.erp.service;

import java.util.List;
import java.util.Map;

/**
 * 季度最小权限复核（add-access-review-console spec：SOP-5.6-D 全链）。
 * 风险分层生成 → 快照三异常 → 逐项判定（理由 L1）→ 台账回收（绕守卫）
 * → 报告双签归档 → 逾期升级（BR-5.6-10）。
 */
public interface UarService {

    /**
     * 生成季度任务：特权/敏感 100% 人工、普通仅基线偏离、基线内自动通过不产生 item；
     * 复核人 = REPORT_TO 主管（按人）→ DEPT_HEAD 回落 → ADMIN 兜底；
     * 同周期未完成重复生成 422；截止 = 生成 +10 工作日（自然日近似 +14 天口径见 design）。
     */
    Map<String, Object> generate();

    /** 任务列表（含完成度统计：总项/已判定/收回） */
    List<Map<String, Object>> taskList();

    /** 任务项列表（taskId 必填；reviewer=me 仅本人待办；含快照解析） */
    List<Map<String, Object>> itemList(String taskId, boolean mineOnly);

    /**
     * 逐项判定：KEEP/RECLAIM；保留长期未使用或超基线角色理由 ≥10 字否则 422（L1）；
     * 非本项复核人 403。
     */
    Map<String, Object> judge(String itemId, String judgment, String reason);

    /** 执行收回（item 级）：台账 reclaimForReview 或直删投影；幂等 */
    Map<String, Object> executeReclaim(String itemId);

    /** 汇总生成报告（全部判定完成自动生成；手动汇总允许 <100%）；task 唯一幂等 */
    Map<String, Object> summarize(String taskId);

    /** ADMIN 确认（双签第一步） */
    void confirmReport(String taskId);

    /** 内审会签（ROLE_UAR_AUDITOR，双签第二步）→ 归档 */
    void signReport(String taskId);

    /** 报告列表（含统计与双签状态） */
    List<Map<String, Object>> reportList();

    /** 逾期升级扫描：REVIEWING 且截止 ≤ now+2d 且完成率 <100% → 升级通知（幂等）+ 12h 重复提醒 */
    Map<String, Object> sweepOverdue();
}
