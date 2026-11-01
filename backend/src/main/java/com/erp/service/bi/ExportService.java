package com.erp.service.bi;

import com.erp.entity.bi.BiExportTask;

import java.util.List;
import java.util.Map;

/**
 * 后台导出（spec bi-query-governance C-4.10-05 + bi-export-lifecycle /
 * bi-export-overlimit-approval，add-export-console）：
 * 模板/数据集双源、三格式水印文件、AES-256 加密落盘、下载 7 天有效、
 * 优先级队列、超时终止、生命周期清理；超量走 ApprovalEngine 分级单签。
 */
public interface ExportService {

    /**
     * 提交导出。
     *
     * @param dataset 数据集（cost / alerts / inv-report）或固定 "template"
     * @param params  参数（templateId/format/purpose 及数据集筛选）
     * @param estRows 预估行数（分级与超量判定）
     */
    Map<String, Object> submit(String dataset, Map<String, Object> params, long estRows);

    /** 旧审批入口（内部改走审批引擎，兼容既有调用） */
    Map<String, Object> approve(String id, boolean ok, String reason);

    /** 我的导出任务（站内通知数据源） */
    List<BiExportTask> myTasks();

    /** 待审批（admin） */
    List<BiExportTask> pendingApproval();

    /** worker 推进一轮（QUEUED → RUNNING → DONE，按优先级消费） */
    int drainOnce();

    /**
     * 下载：属主或 ADMIN、7 天有效，AES 解密流出。
     *
     * @return {"fileName": ..., "contentType": ..., "bytes": [...]}
     */
    Map<String, Object> download(String taskId);

    /** 超时终止（RUNNING >30min → FAILED），返回处理条数 */
    int sweepTimeouts();

    /**
     * 生命周期清理：过期删密文 + CLEAN_AT；24h 内未提醒发提醒 + REMIND_AT。
     *
     * @return [清理条数, 提醒条数]
     */
    int[] sweepLifecycle();

    /** 超量审批回调入口（ApprovalEngine 回调内） */
    void onOverlimitApproved(String taskId);

    /** 超量审批驳回回调入口 */
    void onOverlimitRejected(String taskId);
}
