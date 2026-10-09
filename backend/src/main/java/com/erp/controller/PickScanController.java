package com.erp.controller;

import com.erp.common.R;
import com.erp.service.inv.PickScanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 扫码确认（4.7.2，spec picking-review 行级扫码确认）：
 * 三码校验 / 行确认 / 短少登记。写口限 ADMIN/WAREHOUSE。
 */
@Tag(name = "拣货复核-扫码确认")
@RestController
@RequestMapping("/api/inv/pick-scans")
public class PickScanController {

    private final PickScanService service;

    public PickScanController(PickScanService service) {
        this.service = service;
    }

    @PostMapping("/verify")
    @Operation(summary = "行级三码校验（不符 422+失败记录，批次不符附带差异登记）")
    public R<Map<String, Object>> verify(@RequestBody Map<String, Object> payload) {
        return R.ok(service.verify(str(payload.get("taskId")),
                i(payload.get("lineNo")), str(payload.get("binCode")),
                str(payload.get("itemCode")), str(payload.get("batchNo"))));
    }

    @PostMapping("/confirm-line")
    @Operation(summary = "确认本行（先放行后实拣；短少 422 走差异登记）")
    public R<Map<String, Object>> confirmLine(@RequestBody Map<String, Object> payload) {
        return R.ok(service.confirmLine(str(payload.get("taskId")),
                i(payload.get("lineNo")), dec(payload.get("pickedQty")),
                str(payload.get("serials"))));
    }

    @PostMapping("/register-short")
    @Operation(summary = "短少登记（行 SHORT + QTY 差异 + 销售单差额释放预留）")
    public R<Map<String, Object>> registerShort(@RequestBody Map<String, Object> payload) {
        return R.ok(service.registerShort(str(payload.get("taskId")),
                i(payload.get("lineNo")), dec(payload.get("actualQty")),
                str(payload.get("reason"))));
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Integer i(Object v) {
        return v == null ? null : Integer.valueOf(String.valueOf(v));
    }

    private static BigDecimal dec(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
