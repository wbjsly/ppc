package com.erp.service.bi;

import java.util.List;
import java.util.Map;

/**
 * 临时报表服务（add-adhoc-query-console，spec bi-temp-report）。
 * 存查询定义非快照；打开按访问者重跑；30 天生命周期；转正式报表口径校验。
 */
public interface TempReportService {

    /** 我的 + 共享给我的清单（含剩余天数/共享状态/已转正标记） */
    List<Map<String, Object>> list();

    /** 另存（查询定义快照 + 30 天到期） */
    Map<String, Object> saveAs(Map<String, Object> payload);

    /**
     * 打开（按访问者身份重跑，行级注入+掩码生效）：
     * 创建人 ∨ SHARE_JSON 命中 → 重跑返回分页结果；未授权 404；已归档 410。
     */
    Map<String, Object> open(String id, int page, int size);

    /** 共享设置（覆盖式写 SHARE_JSON，仅创建人可改） */
    Map<String, Object> share(String id, List<Map<String, Object>> shareList);

    /**
     * 转正式报表：生成模板草稿 + diffCheck 口径校验（C-4.10-01）。
     * 不通过 422 带差异清单；通过 register DRAFT 并回填 PROMOTED_TO；重复转正 422。
     */
    Map<String, Object> promote(String id);

    // ---------- 生命周期（AdhocScheduler 消费） ----------

    /** 每日扫描：到期归档 + 前 3 天提醒（去重）；返回处理计数 */
    Map<String, Object> sweepLifecycle();
}
