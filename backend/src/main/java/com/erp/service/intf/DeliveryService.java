package com.erp.service.intf;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.intf.IntfDelivery;

import java.util.Map;

/**
 * 出站事件投递与死信（spec interface-event-delivery，design D3）。
 * 至少一次投递 + 指数退避重试 + 死信工单 + 保留幂等键重放 + 暂存队列上限告警。
 */
public interface DeliveryService {

    /** 一轮扫描：注册新事件 → 并发冲突判定 → 到期投递 → 暂存/死信告警；返回本轮处理条数 */
    int scanAndDeliver();

    /** 人工重放：复用原幂等键；已 DELIVERED 返回「已投递，跳过」 */
    Map<String, Object> replay(String deliveryId);

    /** 人工放弃死信（留痕） */
    Map<String, Object> abandon(String deliveryId, String note);

    /** 投递台账分页（按类型/状态/时间） */
    Page<IntfDelivery> page(long current, long size, String eventType, String status);

    /** 死信工单处理（DONE 留痕，可选重放） */
    Map<String, Object> handleTicket(String ticketId, String note, boolean replay);

    /** 运行总览用：投递状态计数与死信工单数 */
    Map<String, Object> stats();
}
