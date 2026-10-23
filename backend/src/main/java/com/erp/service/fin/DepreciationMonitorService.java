package com.erp.service.fin;

import java.util.List;
import java.util.Map;

/**
 * 财务域折旧监控（M8-6，add-depreciation-monitoring，spec asset-depreciation-monitoring）。
 * 只读消费 EAM 折旧台账与资产卡：期间状态视图（关账前确认已计提）+ 残值预警实时扫描（C-4.6-04）。
 * 不触发计提、不落库（design D1/D2）。
 */
public interface DepreciationMonitorService {

    /**
     * 期间状态列表（YYYYMM 倒序）：每期含 状态（NOT_RUN 未计提 / POSTED 已计提 / REVERSED 已冲回）、
     * 折旧合计、残值预警张数、凭证号与状态、勾稽差异。未计提期间以 NOT_RUN 标识（前端红字）。
     */
    List<Map<String, Object>> periodStatuses();

    /**
     * 期间计提明细下钻：该期逐卡明细（资产卡、原值、当期折旧、累计折旧、NBV、残值预警标记）。
     */
    Map<String, Object> periodDetails(String periodTag);

    /**
     * 残值预警实时扫描（C-4.6-04）：在用未提足卡逐卡 NBV = 原值 − 累计折旧 − 减值，
     * 残值 > NBV × 阈值（DEPRECIATION_RESIDUAL_ALERT 缺省 10%）即预警，按残值占比降序。
     * 当前时点实时计算，不依赖计提运行，不落库。
     */
    Map<String, Object> residualAlerts();

    /**
     * 结账⑦判定辅助：该期间需计提卡数（在用、未提足、已过起提月；与引擎选卡同源条件，
     * design D3——用于「按需阻断」，0 = 本期无折旧义务）。
     */
    long countDepreciableCards(String periodTag);

    /**
     * 结账⑦判定辅助：该期间是否存在 POSTED（未冲回）折旧运行。
     */
    boolean hasPostedRun(String periodTag);
}
