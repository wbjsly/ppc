package com.erp.service.iot;

import java.util.List;
import java.util.Map;

/**
 * 移动待办中心（13.7 聚合 + 30min 超时升级 + 处理回写）。
 */
public interface IotMobileTodoService {

    /** 创建待办（异常/巡检/告警联动入口） */
    Map<String, Object> create(Map<String, Object> body);

    /**
     * 聚合查询：ApprovalEngine 现有审批流待办 + 异常/巡检/升级待办统一视图，
     * 按紧急程度/超时倒计时排序。
     */
    List<Map<String, Object>> aggregate(String assignee, String status);

    /**
     * 处理待办并回写原单（双因子已在上游校验）。
     * 结果回写原审批流/异常单并通知发起人。
     */
    Map<String, Object> handle(String todoId, String opinion, String result);

    /**
     * 超时升级扫描（IotEscalateScheduler 调用）：DUE_AT 过期 PENDING → 升级上级。
     */
    Map<String, Object> scanEscalate();
}
