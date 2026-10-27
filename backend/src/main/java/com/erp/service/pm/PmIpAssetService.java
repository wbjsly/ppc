package com.erp.service.pm;

import com.erp.entity.pm.PmAchievement;

import java.util.Map;

/**
 * IP 资产建档与跟踪（add-ip-management，FR-4.8-6-5 + BR-4.8-26）。
 * 确认同事务自动建档（UK achieve_id 幂等，只增不改）；申请状态四态 + 历史留痕。
 */
public interface PmIpAssetService {

    /** 由已确认成果生成档案（幂等：同一成果仅一份；保护期自动算） */
    Map<String, Object> generateFromAchievement(PmAchievement achievement);

    /** 更新申请状态（写状态历史 log） */
    Map<String, Object> applyStatus(String assetId, Map<String, Object> payload);

    /** 档案台账（类型/权属/状态/到期区间筛选） */
    Map<String, Object> list(Map<String, Object> query);

    /** 状态变更历史 */
    Object statusLog(String assetId);
}
