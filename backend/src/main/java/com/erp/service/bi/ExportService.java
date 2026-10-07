package com.erp.service.bi;

import com.erp.entity.bi.BiExportTask;

import java.util.List;
import java.util.Map;

/**
 * 后台导出（spec bi-query-governance，C-4.10-05）：
 * >EXPORT_ROW_LIMIT 或预估 >30s 转后台任务 + 超量审批；文件带水印；并行 ≤3；完成后站内通知。
 */
public interface ExportService {

    /** 提交导出（dataset: cost / alerts） */
    Map<String, Object> submit(String dataset, Map<String, Object> params, long estRows);

    /** 审批（超量；requireAdmin 由控制器兜底） */
    Map<String, Object> approve(String id, boolean ok, String reason);

    /** 我的导出任务（站内通知数据源） */
    List<BiExportTask> myTasks();

    /** 待审批（admin） */
    List<BiExportTask> pendingApproval();

    /** worker 推进一轮（QUEUED → RUNNING → DONE） */
    int drainOnce();
}
