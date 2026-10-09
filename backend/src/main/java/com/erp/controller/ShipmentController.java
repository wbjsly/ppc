package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.sd.Shipment;
import com.erp.service.sd.ShipmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 销售发货接口（3.7.1 部分 / 3.7.2 合并 / 3.7.3 分批 / 3.7.4 发货确认，
 * spec sales-shipment，tasks 9.10）。
 * 权限：WAREHOUSE / SALES / ADMIN（SecurityConfig）。
 * 拣货作业边界：本控制器不提供拣货任务下发（归库存域 4.7），页面须明示。
 */
@Tag(name = "销售发货")
@RestController
@RequestMapping("/api/sd/shipments")
public class ShipmentController {

    private final ShipmentService service;

    public ShipmentController(ShipmentService service) {
        this.service = service;
    }

    @Operation(summary = "发货单分页")
    @GetMapping
    public R<?> page(@RequestParam(defaultValue = "1") long current,
                     @RequestParam(defaultValue = "20") long size,
                     @RequestParam(required = false) String keyword,
                     @RequestParam(required = false) String status,
                     @RequestParam(required = false) String customerId) {
        return R.ok(service.page(current, size, keyword, status, customerId));
    }

    @Operation(summary = "发货单详情（含来源引用与拣货边界提示）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "部分发货生成（按未发余量，可多次）", description = "3.7.1 / 9.2")
    @PostMapping("/partial")
    public R<Map<String, Object>> partial(@RequestBody Map<String, Object> body) {
        String soId = str(body.get("soId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) body.get("lines");
        return R.ok(service.generatePartial(soId, lines));
    }

    @Operation(summary = "合并发货生成（同客户同仓多 SO）", description = "3.7.2 / 9.3")
    @PostMapping("/merge")
    public R<Map<String, Object>> merge(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> soIds = (List<String>) body.get("soIds");
        return R.ok(service.generateMerge(soIds, str(body.get("warehouseCode"))));
    }

    @Operation(summary = "分批发货生成（按分批方案行，独立交期）", description = "3.7.3 / 9.4")
    @PostMapping("/batch")
    public R<Map<String, Object>> batch(@RequestBody Map<String, Object> body) {
        String soId = str(body.get("soId"));
        String d = str(body.get("asOfDate"));
        LocalDate asOf = d == null || d.isEmpty() ? null : LocalDate.parse(d);
        return R.ok(service.generateBatch(soId, asOf));
    }

    @Operation(summary = "出库过账（FIFO 选批扣库存 + 消耗预留 + AR.CONFIRMED 事件）",
            description = "FR-4.3-6-5，库存不足阻断")
    @PostMapping("/{id}/post")
    public R<Shipment> post(@PathVariable String id) {
        return R.ok(service.post(id));
    }

    @Operation(summary = "发货确认（回写 SO 行，物流异常通知销售）", description = "FR-4.3-6-6")
    @PostMapping("/{id}/confirm")
    public R<Shipment> confirm(@PathVariable String id,
                               @RequestBody Map<String, Object> body) {
        return R.ok(service.confirm(id, str(body.get("logisticsCo")),
                str(body.get("logisticsNo")), str(body.get("exceptNote"))));
    }

    @Operation(summary = "签收登记（SO 全发完 → 已签收）", description = "FR-4.3-6-7")
    @PostMapping("/{id}/sign")
    public R<Shipment> sign(@PathVariable String id) {
        return R.ok(service.sign(id));
    }

    @Operation(summary = "拒收（生成退货申请草稿并关联原发货单）", description = "FR-4.3-6-7")
    @PostMapping("/{id}/reject")
    public R<Shipment> reject(@PathVariable String id,
                              @RequestBody Map<String, Object> body) {
        return R.ok(service.reject(id, str(body.get("reason"))));
    }

    @Operation(summary = "超时未签收预警扫描（手工触发入口）")
    @PostMapping("/sweep-sign-timeout")
    public R<Integer> sweep() {
        return R.ok(service.sweepSignTimeout());
    }

    @Operation(summary = "取消发货单（仅草稿）", description = "9.9")
    @PostMapping("/{id}/cancel")
    public R<Shipment> cancel(@PathVariable String id,
                              @RequestBody Map<String, Object> body) {
        return R.ok(service.cancel(id, str(body.get("reason"))));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
