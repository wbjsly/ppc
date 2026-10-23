package com.erp.controller;

import com.erp.common.R;
import com.erp.service.fin.AmortService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 预提待摊（M8-9，spec prepaid-amortization）：登记/计划/触发生成/冲销/年末检查。
 */
@Tag(name = "预提待摊")
@RestController
@RequestMapping("/api/fin/amort")
public class AmortController {

    private final AmortService service;

    public AmortController(AmortService service) {
        this.service = service;
    }

    @Operation(summary = "预提登记（生成单期计提计划，L1 合计校验）")
    @PostMapping("prepaid")
    public R<Map<String, Object>> registerPrepaid(@RequestBody Map<String, Object> payload) {
        return R.ok(service.registerPrepaid(payload));
    }

    @Operation(summary = "待摊登记（直线法计划尾差并入末期；>12月 L4 提示）")
    @PostMapping("deferred")
    public R<Map<String, Object>> registerDeferred(@RequestBody Map<String, Object> payload) {
        return R.ok(service.registerDeferred(payload));
    }

    @Operation(summary = "登记单列表（type=PREPAY|AMORT，含计划进度）")
    @GetMapping("docs")
    public R<List<Map<String, Object>>> listDocs(@RequestParam(required = false) String type) {
        return R.ok(service.listDocs(type));
    }

    @Operation(summary = "单据详情 + 计划行下钻")
    @GetMapping("docs/{id}")
    public R<Map<String, Object>> docDetail(@PathVariable String id) {
        return R.ok(service.docDetail(id));
    }

    @Operation(summary = "生成本期计提/摊销凭证（幂等；DRAFT 待审核，FR-4.6-8-3/4）")
    @PostMapping("generate")
    public R<Map<String, Object>> generate(@RequestParam String period) {
        return R.ok(service.generate(period));
    }

    @Operation(summary = "预提实际冲销（差异>0.5% 摘要标记，C-4.6-16）")
    @PostMapping("prepaid/{id}/settle")
    public R<Map<String, Object>> settlePrepaid(@PathVariable String id,
                                                @RequestBody Map<String, Object> payload) {
        return R.ok(service.settlePrepaid(id, payload));
    }

    @Operation(summary = "年末余额检查（L4 黄标，FR-4.6-8-6）")
    @GetMapping("year-end-check")
    public R<Map<String, Object>> yearEndCheck(@RequestParam String period) {
        return R.ok(service.yearEndCheck(period));
    }
}
