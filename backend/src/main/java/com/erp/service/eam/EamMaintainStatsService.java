package com.erp.service.eam;

import java.util.Map;

/**
 * 设备履历与维修分析（add-asset-maintenance，design D2，FR-4.13-3-5/11）。
 * 履历=按卡片聚合工单/挂锁/工时/提示；分析=故障次数/MTBF/MTTR/费用趋势（环比 L4 预警）。
 */
public interface EamMaintainStatsService {

    /**
     * 设备履历：工单列表（含成本三构成）+ 挂锁记录 + 工时 + 评估与资本化提示（时间倒序）。
     */
    Map<String, Object> maintenanceHistory(String cardId);

    /**
     * 维修分析（近 N 月，默认 12）：故障次数、MTBF（相邻故障报修间隔天数均值）、
     * MTTR（报修到验收通过小时均值）、维修费用按月趋势、环比异常偏高 L4 预警。
     */
    Map<String, Object> stats(String cardId, int months);
}
