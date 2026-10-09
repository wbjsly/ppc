package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.qms.Exempt;
import com.erp.service.qms.ExemptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 免检管理（BR-4.12-12，/api/qms/exempts）：申请（质量经理审批）+ 列表。 */
@Tag(name = "免检管理")
@RestController
@RequestMapping("/api/qms/exempts")
public class ExemptController {

    private final ExemptService service;

    public ExemptController(ExemptService service) {
        this.service = service;
    }

    @Operation(summary = "免检列表")
    @GetMapping
    public R<List<Exempt>> list(@RequestParam(required = false) String status,
                                @RequestParam(required = false) String materialCode) {
        return R.ok(service.list(status, materialCode));
    }

    @Operation(summary = "提交免检申请（未批准不生效）")
    @PostMapping
    public R<Map<String, Object>> apply(@RequestBody Exempt body) {
        return R.ok(service.apply(body));
    }
}
