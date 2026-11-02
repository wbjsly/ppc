package com.erp.service.iot;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 移动报工薄适配层（13.1，spec iot-mobile：FR-4.11-1-x / BR-4.11-10/11/54）。
 * 复用 shop-floor-reporting 报工单与超产分级，不改其状态机本体；
 * 本层补移动端卡控：首件强制/离线例外/幂等去重/连续不良/72h 推送升级。
 */
public interface IotReportService {

    /**
     * 我的工单（13.1.1 工单接收）：已释放（RELEASED）工单按工位/人员精准推送，
     * 含产品/计划数量/工序/BOM 摘要/CTQ 要求。
     */
    List<Map<String, Object>> myOrders(String userId, String wcCode);

    /**
     * 72h 离线推送升级扫描：终端离线超 OFFLINE_SYNC_MAX_HOURS 未接收工单
     * → 生成车间主任升级待办（IotLockScheduler/手动触发）。
     */
    Map<String, Object> scanPushEscalations();

    /**
     * 现场扫码领料校验（FR-4.11-1-2）：BOM 比对 + 批次有效期 + 容差 + 库存。
     * 物料不匹配/批次过期 → L1 409 硬阻断；超容差（TOLERANCE_DEFAULT）→ 422 需 reason；
     * 库存不足 → 提示 + 缺料预警标记。支持主/辅条码映射。
     *
     * @return {pass, itemCode, itemName, batchNo, requiredQty, checkedQty, warnings:[]}
     */
    Map<String, Object> verifyMaterial(String dispatchId, String scanCode,
                                       BigDecimal qty, String reason);

    /**
     * 首件提交（13.1.2）：录入 CTQ 数据 + 拍照；离线可按「首件待确认」条件放行
     * （OFFLINE_FLAG=1，BR-4.11-10）。
     */
    Map<String, Object> submitFirstArticle(Map<String, Object> body);

    /**
     * IPQC 首件确认（13.1.3 质检确认）：PASS → CONFIRMED 解锁批量报工；
     * REJECTED → 工单侧告警；离线放行批次的复核不合格 → 整批转差异单（BR-4.11-54，
     * 不置工单暂停）。
     */
    Map<String, Object> confirmFirstArticle(String id, boolean pass, String remark);

    /**
     * 移动工序报工（13.1.2）：首件未确认 L1 阻断（离线例外条件放行）；
     * 幂等键（工单+工序+时间戳+设备）去重不重复过账；产出走 ReportingService 超产分级
     * （105% L4）；连续 5 件不良自动质量告警。
     *
     * @return {rptId, status, duplicate, overprodAckRequired, qualityAlert}
     */
    Map<String, Object> reportOutput(Map<String, Object> body);

    /**
     * 报工审核（13.1.4 车间主任）：超产/异常报工单在线审核。
     * pass → 复用超产审批回调放行过账；驳回 → 退回操作工修正。
     */
    Map<String, Object> reviewReport(String rptId, boolean pass, String opinion);

    /** 首件记录查询（派工单维度） */
    Map<String, Object> firstArticleOf(String dispatchId);
}
