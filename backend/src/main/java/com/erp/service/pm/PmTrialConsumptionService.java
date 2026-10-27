package com.erp.service.pm;

import java.util.List;
import java.util.Map;

/**
 * 试制消耗确认 + 样品台账（add-rnd-material，FR-4.8-10-3 + BR-4.8-39）。
 * 一领料单仅一份确认（防重 409）；实耗与领用差异 >10% 无说明 → L4（不阻断）；
 * 样品台账与领料批次关联确保用料可追溯；CONFIRMED 后禁改。
 */
public interface PmTrialConsumptionService {

    /** 消耗确认（isNew 显式；领料单 TRIAL+APPROVED 校验；防重；SURPLUS 服务端算；差异 L4；样品台账随确认落库） */
    Map<String, Object> confirm(Map<String, Object> payload);

    /** 确认单列表（按项目/领料单过滤） */
    Map<String, Object> list(Map<String, Object> query);

    /** 确认单详情（含消耗行 + 样品台账） */
    Map<String, Object> detail(String id);

    /** 样品台账列表（按项目/批次/去向筛选） */
    Map<String, Object> sampleLedger(Map<String, Object> query);
}
