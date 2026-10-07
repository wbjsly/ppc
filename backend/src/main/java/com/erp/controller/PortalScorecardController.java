package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.scm.ScmScorecardResult;
import com.erp.security.PortalContext;
import com.erp.service.scm.ScorecardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 门户记分卡（spec supplier-scorecard：公示查看与申诉，C-4.9-06 仅见自身）。
 * 挂 /api/portal/** —— 由 PortalGuardInterceptor 保证 SUPPLIER 主体与隔离。
 */
@Tag(name = "门户记分卡")
@RestController
@RequestMapping("/api/portal/scorecards")
public class PortalScorecardController {

    private final ScorecardService service;
    private final PortalContext portalContext;

    public PortalScorecardController(ScorecardService service, PortalContext portalContext) {
        this.service = service;
        this.portalContext = portalContext;
    }

    @Operation(summary = "我的记分卡（仅已公示）")
    @GetMapping
    public R<List<ScmScorecardResult>> list() {
        return R.ok(service.portalList(portalContext.requireSupplierId()));
    }

    @Operation(summary = "记分卡详情（跨伙伴不可见）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.portalDetail(id, portalContext.requireSupplierId()));
    }

    @Operation(summary = "发起申诉（公示后 7 个工作日内）")
    @PostMapping("/{id}/appeal")
    public R<Map<String, Object>> appeal(@PathVariable String id,
                                         @RequestBody Map<String, Object> body) {
        body.put("resultId", id);
        String supplierId = portalContext.requireSupplierId();
        // 门户上下文校验（防越权申诉他人记分卡）
        service.portalDetail(id, supplierId);
        return R.ok(service.appeal(body));
    }
}
