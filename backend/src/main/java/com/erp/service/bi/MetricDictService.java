package com.erp.service.bi;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.bi.BiMetricDict;
import com.erp.entity.bi.BiMetricDictHistory;

import java.util.List;
import java.util.Map;

/**
 * 指标口径字典（spec bi-metric-dictionary，design D4）。
 * 七要素登记、发布前字段级 diff 硬阻断（C-4.10-01）、变更反向扫描引用标记。
 */
public interface MetricDictService {

    /** 登记/更新指标（草稿），要素缺失 400；同键新版本另存行（行级历史） */
    Map<String, Object> register(Map<String, Object> payload);

    /** 发布：执行字段级 diff 二次校验（C-4.10-01），通过后置 PUBLISHED 并生效 */
    Map<String, Object> publish(String metricKey, String submittedFormula);

    /** 字典条目公式变更：新版本行 + 反向扫描 USED_BY 置 PENDING_UPDATE */
    Map<String, Object> changeFormula(String metricKey, String newFormula, String reason);

    /** diff 校验执行器：submittedFormula 与字典公式 JSON 规范化逐 key 比对；一致 true */
    Map<String, Object> diffCheck(String metricKey, String submittedFormula);

    /** 引用方登记（页面/记分卡把自身写入 USED_BY） */
    void bindUsage(String metricKey, String consumerId);

    /** 指标当前版本分页 */
    Page<BiMetricDict> page(long current, long size, String status, String keyword);

    /** 历史版本列表 */
    List<BiMetricDictHistory> history(String metricKey);

    BiMetricDict current(String metricKey);

    /** 引用方状态（公式变更后页面口径提示的数据源） */
    Map<String, Object> consumerStatus(String metricKey);
}
