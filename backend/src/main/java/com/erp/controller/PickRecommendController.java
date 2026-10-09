package com.erp.controller;

import com.erp.common.R;
import com.erp.service.inv.PickRecommendService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 拣货推荐（4.6.3，spec outbound-strategy）：队列 A 单据生成《批次推荐表》与确认回写
 * （批次+仓位，不建预留）。写口限 ADMIN/WAREHOUSE（SecurityConfig），服务层再强制。
 */
@Tag(name = "出库策略-拣货推荐")
@RestController
@RequestMapping("/api/inv/pick-recommend")
public class PickRecommendController {

    private final PickRecommendService service;

    public PickRecommendController(PickRecommendService service) {
        this.service = service;
    }

    @PostMapping("/generate")
    @Operation(summary = "生成推荐（只读：逐行 FIFO+FEFO 批次+仓位，不改单据）")
    public R<Map<String, Object>> generate(@RequestBody Map<String, Object> payload) {
        String type = payload.get("type") == null ? null : String.valueOf(payload.get("type"));
        String docNo = payload.get("docNo") == null ? null : String.valueOf(payload.get("docNo"));
        return R.ok(service.generate(type, docNo));
    }

    @PostMapping("/confirm")
    @Operation(summary = "确认回写（批次+仓位写入单据行，改写行落偏离台账，不建预留）")
    public R<Map<String, Object>> confirm(@RequestBody Map<String, Object> payload) {
        return R.ok(service.confirm(payload));
    }

    @PostMapping("/generate-by")
    @Operation(summary = "生成推荐（query 参数入口，便于 Knife4j 调试）")
    public R<Map<String, Object>> generateBy(@RequestParam String type, @RequestParam String docNo) {
        return R.ok(service.generate(type, docNo));
    }
}
