package com.erp.controller;

import com.erp.common.R;
import com.erp.service.inv.OutboundWorkbenchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 出库作业台（4.5.1~4.5.4，spec outbound-workbench）：四类型队列 + 过账/确认双入口
 * （动作委托域服务，与各域页面同一后端动作）。查询仅需认证，动作限 ADMIN/WAREHOUSE。
 * 单据流水抽屉复用既有 /api/inv/transactions（按 BIZ_DOC_NO 过滤），本控制器不重复提供。
 */
@Tag(name = "出库作业台")
@RestController
@RequestMapping("/api/inv/outbound")
public class OutboundWorkbenchController {

    private final OutboundWorkbenchService service;

    public OutboundWorkbenchController(OutboundWorkbenchService service) {
        this.service = service;
    }

    @GetMapping("/{type}/queue")
    @Operation(summary = "按类型队列（queue=A 待处理 / B 已处理，空=合并）")
    public R<Map<String, Object>> queue(@PathVariable String type,
                                        @RequestParam(required = false) String queue,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(defaultValue = "1") long current,
                                        @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.queue(type, queue, keyword, current, size));
    }

    @PostMapping("/{type}/{id}/post")
    @Operation(summary = "过账（委托域服务：发货/领料/调拨出库/报废）")
    public R<Map<String, Object>> post(@PathVariable String type, @PathVariable String id) {
        return R.ok(service.post(type, id));
    }

    @PostMapping("/SALES_OUT/{id}/confirm")
    @Operation(summary = "发货确认（仅 SALES_OUT，POSTED → CONFIRMED）")
    public R<Map<String, Object>> confirm(@PathVariable String id,
                                          @RequestParam(required = false) String logisticsCo,
                                          @RequestParam(required = false) String logisticsNo) {
        return R.ok(service.confirm(id, logisticsCo, logisticsNo));
    }
}
