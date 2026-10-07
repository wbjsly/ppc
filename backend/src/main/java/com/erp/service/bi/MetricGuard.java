package com.erp.service.bi;

import com.erp.common.ServiceException;
import com.erp.entity.bi.BiMetricDict;
import org.springframework.stereotype.Component;

/**
 * 未注册指标统一拦截（spec bi-metric-dictionary Requirement: 未注册指标不可呈现）。
 * 分析查询入口必须先 guard：未注册 → 422 而非静默降级；PENDING_UPDATE → 允许取数但由页面提示口径变更。
 */
@Component
public class MetricGuard {

    private final MetricDictService metricDictService;

    public MetricGuard(MetricDictService metricDictService) {
        this.metricDictService = metricDictService;
    }

    /** 校验指标已注册（PUBLISHED 或 PENDING_UPDATE 均可取数），否则 422 */
    public BiMetricDict require(String metricKey) {
        BiMetricDict d = metricDictService.current(metricKey);
        if (d == null) {
            throw new ServiceException(422, "指标未注册口径：" + metricKey + "，请先在口径字典登记并发布");
        }
        if (d.getStatus() != null && !BiMetricDict.ST_PUBLISHED.equals(d.getStatus())
                && !BiMetricDict.ST_PENDING_UPDATE.equals(d.getStatus())) {
            throw new ServiceException(422, "指标口径未发布（当前 " + d.getStatus() + "）：" + metricKey);
        }
        return d;
    }

    /** 批量校验，返回首个失败 */
    public void requireAll(String... metricKeys) {
        for (String k : metricKeys) {
            require(k);
        }
    }
}
