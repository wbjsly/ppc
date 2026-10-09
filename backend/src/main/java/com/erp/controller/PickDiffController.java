package com.erp.controller;

import com.erp.common.R;
import com.erp.service.inv.PickDiffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 差异处理（4.7.4，spec picking-review 差异单台账与闭环）：
 * 台账分页（DIFF_TYPE=PICK）+ 主管闭环（处理说明必填）；盘点差异 4.11 组处理。
 */
@Tag(name = "拣货复核-差异处理")
@RestController
@RequestMapping("/api/inv/pick-diffs")
public class PickDiffController {

    private final PickDiffService service;

    public PickDiffController(PickDiffService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "差异台账分页（状态/类型/单据/物料筛选）")
    public R<Map<String, Object>> page(@RequestParam(required = false) String status,
                                       @RequestParam(required = false) String kind,
                                       @RequestParam(required = false) String docNo,
                                       @RequestParam(required = false) String itemCode,
                                       @RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.page(status, kind, docNo, itemCode, current, size));
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "闭环（处理说明必填，全部 RESOLVED 后过账门闩解除）")
    public R<Map<String, Object>> close(@PathVariable String id,
                                        @RequestBody Map<String, Object> payload) {
        String note = payload.get("note") == null ? null : String.valueOf(payload.get("note"));
        return R.ok(service.close(id, note));
    }

    @PostMapping("/register")
    @Operation(summary = "登记差异（短少/批次不符/复核差异统一入口，幂等）")
    public R<Map<String, Object>> register(@RequestBody Map<String, Object> payload) {
        String taskId = str(payload.get("taskId"));
        Integer lineNo = payload.get("lineNo") == null ? null
                : Integer.valueOf(String.valueOf(payload.get("lineNo")));
        String kind = str(payload.get("kind"));
        String reason = str(payload.get("reason"));
        java.math.BigDecimal expect = dec(payload.get("expectQty"));
        java.math.BigDecimal actual = dec(payload.get("actualQty"));
        return R.ok(Map.of("diff", service.registerDiff(taskId, lineNo, kind, expect, actual,
                reason)));
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static java.math.BigDecimal dec(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return new java.math.BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
