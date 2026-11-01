package com.erp.service.ops;

import java.util.List;
import java.util.Map;

/**
 * 升级引擎（18.3，spec ops-sla）：P1 认领超时升级 / 层级 L1→L2→L3 / 复盘逾期。
 */
public interface EscalationService {

    /**
     * 扫描一轮升级（调度每 5 分钟调用）：
     * ① OPEN 告警按层级超时判定（P1 > P1_RESPONSE_MIN 未认领 → 升级改派；
     *    层级推送后 > ESCALATION_RESPONSE_MIN 无处置 → L1→L2→L3）
     * ② 复盘逾期（P1/P2 工单关闭超 5 工作日 REPORT_STATUS 未生成 → 升级）
     * 幂等：已升级告警不重复升级。
     *
     * @return 本轮处理（升级/改派/复盘提醒）条数
     */
    int scanAndEscalate();

    /** 升级日志（18.3.2 层级升级视图） */
    List<Map<String, Object>> escalationLogs(String alertId, int limit);

    /** 按值班表取某日某层级的值班人（无则返回 null） */
    String oncallOf(java.time.LocalDate date, String level);
}
