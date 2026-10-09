package com.erp.service.inv;

import java.util.Map;

/**
 * 出库作业台（4.5.1~4.5.4，spec outbound-workbench，design D2）。
 * 按类型聚合四域出库队列；过账/确认动作一律委托域服务（同一后端动作，镜像各域页面）。
 * 队列口径：SALES_OUT A=DRAFT/B=POSTED；MATERIAL_OUT A=DRAFT/B=POSTED；
 * TRANSFER_OUT A=DRAFT/B=OUT_POSTED；SCRAP_OUT A=APPROVED/B=POSTED（终态不进队列）。
 */
public interface OutboundWorkbenchService {

    /**
     * 队列查询（归一化行：docId/docNo/status/sub1/sub2/qty/amount/itemSummary/note/createDate）。
     *
     * @param type    SALES_OUT / MATERIAL_OUT / TRANSFER_OUT / SCRAP_OUT
     * @param queue   A（待处理）/ B（已处理待下一步）；空 = 两队列合并
     * @param keyword 来源单号/物料/类型附属字段（客户/仓库/工单/NCR）模糊匹配
     */
    Map<String, Object> queue(String type, String queue, String keyword,
                              long current, long size);

    /** 过账（委托域服务：Shipment.post / MaterialIssue.post / TransferOrder.postOut / ScrapOrder.post） */
    Map<String, Object> post(String type, String docId);

    /** 发货确认（仅 SALES_OUT，委托 Shipment.confirm——SO 回写/应收事件由销售域承载） */
    Map<String, Object> confirm(String docId, String logisticsCo, String logisticsNo);
}
