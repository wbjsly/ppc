package com.erp.service.fin;

import java.util.Map;

/**
 * 税务风险汇聚看板（tax-risk-monitoring）：8 类信号聚合，跨叶只读、单源失败降级。
 */
public interface RiskDashboardService {

    /**
     * 看板聚合（design D5）：本叶 3 类（证书到期/不可用、优惠冻结/终止/临期、税负率超界）
     * + 跨叶只读 5 类（认证临期、出口退税 60/30 天与超期、逾期申报、跨期调整待办、频繁红冲标记）。
     * 每个来源独立容错：失败 → available=false 标注，其余照常。
     */
    Map<String, Object> dashboard(String periodEnd);
}
