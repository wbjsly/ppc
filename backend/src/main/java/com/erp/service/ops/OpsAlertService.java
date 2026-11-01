package com.erp.service.ops;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 运维告警服务（18.2，spec ops-sla）：告警产生/风暴聚合抑制/P1 豁免/台账。
 */
public interface OpsAlertService {

    /**
     * 产生一条告警：落库 + 风暴判定（同 SOURCE 滚动 1 分钟 > 阈值 → 聚合根因+抑制）
     * + 推送（P1 立即站内信最高优先级；P2/P3 普通站内信）。
     *
     * @return 告警 ID
     */
    String raise(String level, String source, String title, String detail,
                 BigDecimal metricValue, BigDecimal threshold, String eventType);

    /** 告警台账（级别/来源/状态筛选，时间倒序） */
    List<Map<String, Object>> list(String level, String source, String status, int page, int size);

    /** 认领告警（值班工程师） */
    void claim(String alertId, String handler);

    /** 处置完成 */
    void resolve(String alertId, String note);

    /** 风暴聚合视图：抑制窗口内的根因事件与影响面 */
    List<Map<String, Object>> stormRootCauses(int limit);
}
