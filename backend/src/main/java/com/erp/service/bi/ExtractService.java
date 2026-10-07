package com.erp.service.bi;

import com.erp.entity.bi.BiExtractTask;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 抽取调度（spec bi-data-pipeline，design D9）。
 * DAG：PO(10)→GR(20)→AP(30)→COST_SNAPSHOT(40)→PRICE_ALERT(50)→COST_SAVE(60)，
 * 上游非 READY 下游挂起；失败重试 ≤3；波动 >10 倍 PAUSED 待人工确认。
 */
public interface ExtractService {

    /** 执行一批（幂等：已 READY 跳过；返回本批处理摘要） */
    Map<String, Object> runBatch(LocalDate date, boolean manual);

    /** 批次任务列表（页面数据截止/状态展示） */
    List<BiExtractTask> batchTasks(LocalDate date);

    /** 最近 READY 的快照批次（分析页数据截止时间） */
    Map<String, Object> readySnapshot();

    /** 波动暂停后人工确认继续 */
    Map<String, Object> confirmPause(String taskId);
}
