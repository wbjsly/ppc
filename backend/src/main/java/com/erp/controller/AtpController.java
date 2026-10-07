package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.sd.AtpTrial;
import com.erp.service.sd.AtpService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * ATP 承诺与分批交付接口（3.4.1 承诺试算 / 3.4.2 分批交付，
 * spec sales-atp-reservation，tasks 8.9）。
 * 权限：SALES / WAREHOUSE / ADMIN（SecurityConfig）。
 */
@Tag(name = "ATP 承诺与分批交付")
@RestController
@RequestMapping("/api/sd/atp")
public class AtpController {

    private final AtpService service;

    public AtpController(AtpService service) {
        this.service = service;
    }

    @Operation(summary = "承诺试算（四因子 + 延迟剔除 + 安全库存 + L4 提示）",
            description = "BR-4.3-19/20/21/22，C-4.3-02 承诺阶段 L4")
    @PostMapping("/trial")
    public R<Map<String, Object>> trial(@RequestBody Map<String, Object> body) {
        String itemCode = str(body.get("itemCode"));
        String wh = str(body.get("warehouseCode"));
        Object q = body.get("qty");
        BigDecimal qty = q == null ? null : new BigDecimal(String.valueOf(q));
        String expect = str(body.get("expectDate"));
        LocalDate expectDate = expect == null || expect.isEmpty() ? null : LocalDate.parse(expect);
        return R.ok(service.trial(itemCode, wh, qty, expectDate));
    }

    @Operation(summary = "保存试算记录")
    @PostMapping("/trials")
    public R<AtpTrial> saveTrial(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) body.get("result");
        return R.ok(service.saveTrial(result, str(body.get("remark"))));
    }

    @Operation(summary = "试算记录分页")
    @GetMapping("/trials")
    public R<?> trialPage(@RequestParam(defaultValue = "1") long current,
                          @RequestParam(defaultValue = "20") long size,
                          @RequestParam(required = false) String itemCode,
                          @RequestParam(required = false) String status) {
        return R.ok(service.trialPage(current, size, itemCode, status));
    }

    @Operation(summary = "分批交付拆行（守恒校验 + 逐行交期与预留绑定）",
            description = "FR-4.3-3-6 / BR-4.3-23：Σ = 原行，不等回滚")
    @PostMapping("/lines/{lineId}/split")
    public R<Map<String, Object>> split(@PathVariable String lineId,
                                        @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> batches = (List<Map<String, Object>>) body.get("batches");
        return R.ok(service.splitLine(lineId, batches));
    }

    @Operation(summary = "SO 行级 ATP 检查（C-4.3-02 L4 提示数据）")
    @GetMapping("/so/{soId}/lines")
    public R<List<Map<String, Object>>> checkSoLines(@PathVariable String soId) {
        return R.ok(service.checkSoLines(soId));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
