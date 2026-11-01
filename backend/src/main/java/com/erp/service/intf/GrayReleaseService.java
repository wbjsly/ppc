package com.erp.service.intf;

import java.util.List;
import java.util.Map;

/**
 * 凭证/版本灰度切流量（add-intf-portal-menus，spec interface-canary-release，
 * 对齐 SOP-5.5-B 步4 计划内轮换灰度切流量）。
 * 双凭证并行 + 阶梯百分比切量 + 观察窗口错误率超阈自动回滚；高危动作限 ADMIN。
 */
public interface GrayReleaseService {

    /** 配置灰度规则（ADMIN；阶梯 JSON 如 [10,50,100]） */
    Map<String, Object> createRelease(Map<String, Object> payload);

    /** 灰度规则列表 */
    List<Map<String, Object>> listReleases();

    /** 灰度规则详情（含切量记录） */
    Map<String, Object> getRelease(String id);

    /** 执行切量：CURRENT_STEP+1，按下一档百分比路由新凭证（ADMIN，留痕） */
    Map<String, Object> stepUp(String id);

    /** 手动回滚到上一档（首档则中止；ADMIN，留痕） */
    Map<String, Object> rollback(String id);

    /**
     * 观察窗口错误率监控（调度任务每分钟调用）：新凭证错误率超阈
     * → 自动回滚到上一档（首档则 ABORTED），告警+留痕；返回处理数。
     */
    int observeAndAutoRollback();

    /**
     * 网关只读分流 hook（design D4）：入站 API Key 命中某 RUNNING 灰度规则的旧凭证时，
     * 按当前档位百分比决定是否改路由至新凭证；未命中或无 RUNNING 规则返回原值走原逻辑。
     * requestId 用于按请求哈希取模分桶（确定性，不改限流/熔断判定）。
     */
    String remapApiKey(String inboundApiKey, String requestId);
}
