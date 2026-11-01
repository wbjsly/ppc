package com.erp.service.bi;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.bi.BiMetricDict;
import com.erp.entity.bi.BiMetricDictHistory;

import java.util.List;
import java.util.Map;

/**
 * 指标口径字典（spec bi-metric-dictionary，add-metric-dict-console 增强）。
 * 十二要素登记、发布前字段级 diff 硬阻断（C-4.10-01）、
 * 变更两段式治理（治理单签 → GRAYING 灰度 → 切换双审 BR-4.10-04/C-4.10-08）、
 * 批量口径校验（14.3.3）。
 */
public interface MetricDictService {

    /** 登记/更新指标（草稿），要素缺失 400；新 key 编码格式校验；同键未发布行就地更新 */
    Map<String, Object> register(Map<String, Object> payload);

    /** 发布：RATIO 型分子分母必填；执行字段级 diff 二次校验（C-4.10-01），通过后置 PUBLISHED 并生效 */
    Map<String, Object> publish(String metricKey, String submittedFormula);

    /**
     * 发起口径变更（场景四）：新版本行 CHANGE_PENDING + 治理单签审批；
     * 审批通过进灰度（旧版仍生效），切换确认走双审（C-4.10-08）。
     */
    Map<String, Object> requestChange(String metricKey, Map<String, Object> payload);

    /** 变更列表（CHANGE_PENDING/GRAYING/ABORTED），灰度看板数据源 */
    List<Map<String, Object>> changes(String metricKey);

    /**
     * 切换确认：附新旧口径对比说明；差异超 TOLERANCE(0.5%) 须治理复核备注；
     * 提交双审（数据治理 + 业务负责人）。
     */
    Map<String, Object> switchConfirm(String rowId, String diffNote,
                                     String diffPct, String reviewNote);

    /** 终止灰度：新版 ABORTED 留痕，旧版续用 */
    Map<String, Object> abortChange(String rowId, String reason);

    /** 引用方更新确认：清除 PENDING_UPDATE 标记（历史变更记录保留） */
    Map<String, Object> confirmConsumer(String metricKey, String consumerId);

    /** diff 校验执行器：submittedFormula 与字典公式 JSON 规范化逐 key 比对；一致 true */
    Map<String, Object> diffCheck(String metricKey, String submittedFormula);

    /** 批量口径校验（14.3.3）：PUBLISHED 模板 × 字典当前版，模板×指标×结论现算报告 */
    List<Map<String, Object>> checkAll();

    /** 引用方登记（页面/记分卡把自身写入 USED_BY） */
    void bindUsage(String metricKey, String consumerId);

    /** 指标当前版本分页 */
    Page<BiMetricDict> page(long current, long size, String status, String keyword);

    /** 历史版本列表 */
    List<BiMetricDictHistory> history(String metricKey);

    BiMetricDict current(String metricKey);

    /** 引用方状态（公式变更后页面口径提示的数据源） */
    Map<String, Object> consumerStatus(String metricKey);

    // ---- 回调入口（MetricDictChangeCallback 调用）----

    /** 治理单签通过：CHANGE_PENDING → GRAYING + 反扫引用方 + 通知（旧版仍生效） */
    void onGovernanceApproved(String rowId);

    /** 治理驳回：→ DRAFT 留痕，可修改后重新提交 */
    void onGovernanceRejected(String rowId);

    /** 切换双审通过：新版生效（IS_CURRENT 切换）+ 血缘刷新 + 通知 */
    void applyApprovedChange(String rowId);

    /** 切换驳回：新版保持 GRAYING，可修正后重新发起切换 */
    void onSwitchRejected(String rowId);
}
