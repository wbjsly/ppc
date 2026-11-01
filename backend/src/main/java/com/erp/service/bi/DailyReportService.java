package com.erp.service.bi;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 日报订阅（add-daily-report-subscription，spec bi-daily-report）。
 * 14.8.1 订阅配置（日报定义 CRUD + 本人订阅/退订，BR-4.10-35 指标超权限剔除+L4 提示）
 * + 14.8.2 自动推送（就绪检查→差异化 PDF→三通道推送→触达统计）。
 */
public interface DailyReportService {

    // ---- 日报定义（写限 BI_DESIGNER/ADMIN，Controller 校验） ----

    /** 日报定义列表（含订阅数统计） */
    List<Map<String, Object>> listDefs();

    /** 日报定义详情（含指标清单） */
    Map<String, Object> getDef(String id);

    /** 保存日报定义（新增/更新；kpiKey 须存在于 14.7 kpi_def 否则 422） */
    Map<String, Object> saveDef(Map<String, Object> payload);

    /** 停用/启用日报定义 */
    void changeDefStatus(String id, String status);

    /** 删除日报定义（逻辑删，存在 ACTIVE 订阅时 422 拒绝） */
    void deleteDef(String id);

    // ---- 订阅（本人维度，写限本人） ----

    /** 本人订阅清单（含日报名/通道/状态/剩余未阅读天数） */
    List<Map<String, Object>> mySubscriptions();

    /**
     * 创建订阅（BR-4.10-35：指标清单中超出本人权限的 KPI 自动剔除并 L4 站内信提示，
     * 不整单失败）。
     *
     * @return 含 id 与 excludedKpis（被剔除指标清单）的 Map
     */
    Map<String, Object> saveSubscription(Map<String, Object> payload);

    /** 更新订阅通道 */
    void updateSubscription(String id, List<String> channels);

    /** 退订（STATUS→DISABLED 留痕，后续推送不再包含） */
    void unsubscribe(String id);

    // ---- 推送与触达（管理视图限 BI_DESIGNER/ADMIN） ----

    /** 推送日志分页（含通道/状态/重试/降级/数据截止/质量标注） */
    Map<String, Object> pagePushLogs(String receiver, String status, int page, int size);

    /** 触达统计（送达率/阅读率/降级数，按当前自然月） */
    Map<String, Object> stats();

    /** 阅读回执（打开日报详情页调用；仅本人可标记） */
    void markRead(String pushLogId);

    /** 手动触发试推（BI_DESIGNER/ADMIN）：立即就绪检查+全量推送 */
    Map<String, Object> pushNow(String reportId);

    // ---- 调度入口（Scheduler 调用） ----

    /**
     * 每日推送主流程（07:00 cron）：就绪检查（未就绪延迟重查上限 REPORT_READY_WAIT_MIN）
     * → 逐订阅差异化生成 → 三通道推送 → 推送日志落库。
     *
     * @param pushDate 业务推送日期（通常 T-1 数据日）
     * @return 汇总（ready/generated/sent/degraded/failed 计数）
     */
    Map<String, Object> pushAll(LocalDate pushDate);

    /** 扫描连续 REPORT_UNREAD_DAYS 天未阅读的 ACTIVE 订阅 → L4 站内信提示（30 天去重） */
    int scanUnreadAndPrompt();
}
