package com.erp.service.ops;

import java.util.List;
import java.util.Map;

/**
 * 运维 SLA 月度报告（18.5，spec ops-sla，BR-5.8-07/16）。
 */
public interface OpsSlaReportService {

    /**
     * 生成指定月份（yyyy-MM）报告：五指标计算（变更成功率/告警响应/升级及时率/容量水位/同步延迟达成），
     * 未达标项自动生成改进项（incident，BR-5.8-07，30 天时限）。
     *
     * @return 报告快照
     */
    Map<String, Object> generate(String period);

    /** 报告快照（按月份读最近一次生成结果） */
    Map<String, Object> report(String period);

    /** 归档（DRAFT→ARCHIVED，版本锁定） */
    Map<String, Object> archive(String period);

    /** 对外发布（非 ARCHIVED 422 阻断 BR-5.8-16，C-0-01） */
    Map<String, Object> publish(String period, String scope);

    /** 改进项台账（incident IS_IMPROVE=1） */
    List<Map<String, Object>> improvements(String status, int limit);
}
