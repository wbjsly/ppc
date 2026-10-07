package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.sd.So;
import com.erp.service.sd.SoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 销售订单接口（3.5.1 订单创建 / 3.5.2 订单审批 / 3.5.3 订单变更 / 3.5.4 订单关闭，
 * spec sales-order，tasks 7.11）。
 * 路由权限：SALES / SALES_MGR / SALES_DIRECTOR / ADMIN（SecurityConfig）；
 * 关闭与付款条件确认在服务层再收紧至 MGR+。
 */
@Tag(name = "销售订单")
@RestController
@RequestMapping("/api/sd/so")
public class SoController {

    private final SoService service;

    public SoController(SoService service) {
        this.service = service;
    }

    @Operation(summary = "订单分页")
    @GetMapping
    public R<?> page(@RequestParam(defaultValue = "1") long current,
                     @RequestParam(defaultValue = "20") long size,
                     @RequestParam(required = false) String keyword,
                     @RequestParam(required = false) String status,
                     @RequestParam(required = false) String customerId) {
        return R.ok(service.page(current, size, keyword, status, customerId));
    }

    @Operation(summary = "订单详情（头+行+价格匹配记录+付款条件差异）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "审批日志（档位链与加签节点）")
    @GetMapping("/{id}/approval-logs")
    public R<Map<String, Object>> approvalLogs(@PathVariable String id) {
        return R.ok(service.approvalLogs(id));
    }

    @Operation(summary = "创建订单（价格协议绑定 + 卡控）", description = "FR-4.3-4-1/2/3")
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) body.get("lines");
        return R.ok(service.create(body, lines));
    }

    @Operation(summary = "修改草稿（重新取价）")
    @PutMapping
    public R<Map<String, Object>> update(@RequestBody Map<String, Object> body) {
        String id = (String) body.get("id");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) body.get("lines");
        return R.ok(service.update(id, body, lines));
    }

    @Operation(summary = "手动改价（锁行 + 价格变更审批）", description = "BR-4.3-26")
    @PostMapping("/lines/{lineId}/price")
    public R<?> changePrice(@PathVariable String lineId,
                            @RequestBody Map<String, Object> body) {
        Object p = body.get("unitPrice");
        BigDecimal price = p == null ? null : new BigDecimal(String.valueOf(p));
        return R.ok(service.changePrice(lineId, price, str(body.get("reason"))));
    }

    @Operation(summary = "提交审批（金额三档 + 链尾加签；小额自动确认）",
            description = "FR-4.3-4-5")
    @PostMapping("/{id}/submit")
    public R<Map<String, Object>> submit(@PathVariable String id) {
        return R.ok(service.submit(id));
    }

    @Operation(summary = "付款条件差异的销售主管确认", description = "BR-4.3-25")
    @PostMapping("/{id}/confirm-payment-terms")
    public R<?> confirmPaymentTerms(@PathVariable String id,
                                    @RequestBody Map<String, Object> body) {
        return R.ok(service.confirmPaymentTerms(id, str(body.get("reason"))));
    }

    @Operation(summary = "行交期客户确认回执（清除待确认标记）", description = "BR-4.3-28")
    @PostMapping("/lines/{lineId}/ack-delivery")
    public R<?> ackDelivery(@PathVariable String lineId) {
        return R.ok(service.ackDelivery(lineId));
    }

    @Operation(summary = "订单变更（重跑信用/ATP，已执行行阻断）",
            description = "FR-4.3-4-7 / BR-4.3-30 / C-4.3-08")
    @PutMapping("/{id}/change")
    public R<Map<String, Object>> change(@PathVariable String id,
                                         @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) body.get("lines");
        return R.ok(service.change(id, lines, str(body.get("reason"))));
    }

    @Operation(summary = "订单关闭（释放预留，销售经理确认）", description = "3.5.4")
    @PostMapping("/{id}/close")
    public R<?> close(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(service.close(id, str(body.get("reason"))));
    }

    @Operation(summary = "关闭单不可重启（恒 422 提示新建 SO）", description = "业务逻辑 6")
    @PostMapping("/{id}/reopen")
    public R<?> reopen(@PathVariable String id) {
        service.reopen(id);
        return R.ok(null);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
