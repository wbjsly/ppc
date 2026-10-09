package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.inv.ExpiryEval;
import com.erp.service.inv.ExpiryEvalService;
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
 * 效期质量评估（4.10.3，spec expiry-management 需求④）。
 * 查询认证即可；发起/判定写口服务层质量角色分流（接口管可为）；
 * 审批签署复用 /api/qms/approvals（底座 ROLE_REQUIRED + 同人拦截，BIZ=ExpiryEval）。
 */
@Tag(name = "效期管理-质量评估")
@RestController
@RequestMapping("/api/inv/expiry-evals")
public class ExpiryEvalController {

    private final ExpiryEvalService service;

    public ExpiryEvalController(ExpiryEvalService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "发起评估（仅锁定批次，质量角色，评估说明必填）")
    public R<ExpiryEval> submit(@RequestBody Map<String, Object> body) {
        return R.ok(service.submit(str(body.get("itemCode")), str(body.get("batchNo")),
                str(body.get("evalNote")), str(body.get("remark"))));
    }

    @PostMapping("/{id}/conclusion")
    @Operation(summary = "提交判定三分支（SCRAP/RELEASE/FREEZE，待判定状态）",
            description = "RELEASE 放行有效期必填且晚于今日；FREEZE 即刻关闭并挂 4.9 审批")
    public R<Map<String, Object>> conclusion(@PathVariable String id,
                                             @RequestBody Map<String, Object> body) {
        return R.ok(service.submitConclusion(id, str(body.get("conclusion")),
                str(body.get("releaseUntil")), str(body.get("evalNote"))));
    }

    @GetMapping
    @Operation(summary = "评估单列表（状态/物料/关键词分页）")
    public R<Map<String, Object>> page(@RequestParam(required = false) String status,
                                       @RequestParam(required = false) String itemCode,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.page(status, itemCode, keyword, current, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "评估单详情")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
