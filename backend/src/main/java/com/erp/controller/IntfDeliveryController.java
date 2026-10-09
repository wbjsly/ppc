package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.dao.intf.IntfTicketDao;
import com.erp.entity.intf.IntfDelivery;
import com.erp.entity.intf.IntfTicket;
import com.erp.security.IntfGuard;
import com.erp.service.intf.DeliveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 事件投递台账与死信处置（spec interface-event-delivery，菜单 2.8.3「事件与报文」）。
 * 重放由 INTF_OPS/ADMIN 执行（保留原幂等键），放弃为高危动作限 ADMIN。
 */
@Tag(name = "接口事件投递")
@RestController
@RequestMapping("/api/intf/deliveries")
public class IntfDeliveryController {

    private final DeliveryService service;
    private final IntfTicketDao ticketDao;

    public IntfDeliveryController(DeliveryService service, IntfTicketDao ticketDao) {
        this.service = service;
        this.ticketDao = ticketDao;
    }

    @Operation(summary = "投递台账分页（按事件类型/状态）")
    @GetMapping
    public R<Page<IntfDelivery>> page(@RequestParam(defaultValue = "1") long current,
                                      @RequestParam(defaultValue = "10") long size,
                                      @RequestParam(required = false) String eventType,
                                      @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, eventType, status));
    }

    @Operation(summary = "投递状态统计（运行总览 KPI 用）")
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(service.stats());
    }

    @Operation(summary = "立即执行一轮扫描投递（页面手动触发/测试用）")
    @PostMapping("/scan")
    public R<Integer> scan() {
        return R.ok(service.scanAndDeliver());
    }

    @Operation(summary = "人工重放（保留原幂等键；已投递返回跳过）")
    @PostMapping("/{id}/replay")
    public R<Map<String, Object>> replay(@PathVariable String id) {
        return R.ok(service.replay(id));
    }

    @Operation(summary = "死信放弃（高危：仅 ADMIN）")
    @PostMapping("/{id}/abandon")
    public R<Map<String, Object>> abandon(@PathVariable String id,
                                          @RequestBody(required = false) Map<String, Object> body) {
        IntfGuard.requireAdmin("死信放弃");
        Object note = body == null ? null : body.get("note");
        return R.ok(service.abandon(id, note == null ? "" : String.valueOf(note)));
    }

    @Operation(summary = "人工处理工单列表")
    @GetMapping("/tickets")
    public R<List<IntfTicket>> tickets(@RequestParam(required = false) String status) {
        List<IntfTicket> list = ticketDao.selectList(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<IntfTicket>()
                .eq(status != null && !status.trim().isEmpty(), IntfTicket::getStatus, status == null ? "" : status.trim())
                .orderByDesc(IntfTicket::getCreateDate));
        return R.ok(list);
    }

    @Operation(summary = "处置工单（可选同时重放原事件）")
    @PostMapping("/tickets/{id}/handle")
    public R<Map<String, Object>> handle(@PathVariable String id,
                                         @RequestBody(required = false) Map<String, Object> body) {
        boolean replay = body != null && Boolean.TRUE.equals(body.get("replay"));
        Object note = body == null ? null : body.get("note");
        if (!replay && (note == null || String.valueOf(note).trim().isEmpty())) {
            throw new ServiceException(400, "关闭工单必须填写处理说明");
        }
        return R.ok(service.handleTicket(id, note == null ? "" : String.valueOf(note), replay));
    }
}
