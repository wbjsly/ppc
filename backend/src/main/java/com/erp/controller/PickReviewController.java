package com.erp.controller;

import com.erp.common.R;
import com.erp.service.inv.PickReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 出库复核（4.7.3，spec picking-review 出库复核三分支）。
 * 写口限 ADMIN/WAREHOUSE。
 */
@Tag(name = "拣货复核-出库复核")
@RestController
@RequestMapping("/api/inv/pick-reviews")
public class PickReviewController {

    private final PickReviewService service;

    public PickReviewController(PickReviewService service) {
        this.service = service;
    }

    @PostMapping("/review")
    @Operation(summary = "行级复核结论（PASS/DIFF/QUALITY 三分支）")
    public R<Map<String, Object>> review(@RequestBody Map<String, Object> payload) {
        return R.ok(service.review(str(payload.get("taskId")), i(payload.get("lineNo")),
                str(payload.get("result")), str(payload.get("kind")),
                str(payload.get("reason")), dec(payload.get("expectQty")),
                dec(payload.get("actualQty"))));
    }

    @PostMapping("/return-to-pick")
    @Operation(summary = "退回补拣（DIFF_PENDING → PICKING，行回待拣）")
    public R<Map<String, Object>> returnToPick(@RequestBody Map<String, Object> payload) {
        return R.ok(service.returnToPick(str(payload.get("taskId")),
                i(payload.get("lineNo"))));
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
