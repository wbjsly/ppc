package com.erp.controller;

import com.erp.common.R;
import com.erp.service.inv.BinAssignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 仓位分配（4.4.5，spec bin-assignment；仅需认证的读 + ADMIN/WAREHOUSE 写——
 * 菜单管可见、接口管可为，写口服务层再强制一次）。
 */
@Tag(name = "仓位分配")
@RestController
@RequestMapping("/api/inv/bin-assignment")
public class BinAssignmentController {

    private final BinAssignmentService service;

    public BinAssignmentController(BinAssignmentService service) {
        this.service = service;
    }

    @GetMapping("/pending-gr-lines")
    @Operation(summary = "Tab A：待分配 GR 行（CREATED×检验放行×核销量>0）")
    public R<Map<String, Object>> pending(@RequestParam(required = false) String keyword,
                                          @RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.pendingGrLines(keyword, current, size));
    }

    @GetMapping("/candidates")
    @Operation(summary = "查看建议：该 GR 行 Top3 候选（不落库）")
    public R<List<Map<String, Object>>> candidates(@RequestParam String grNo,
                                                   @RequestParam Integer lineNo) {
        return R.ok(service.candidates(grNo, lineNo));
    }

    @PostMapping("/recommend")
    @Operation(summary = "推荐（RECOMMENDED；无候选挂起+通知）")
    public R<Map<String, Object>> recommend(@RequestParam String grNo,
                                            @RequestParam Integer lineNo) {
        return R.ok(service.recommend(grNo, lineNo));
    }

    @PostMapping("/recommend-all")
    @Operation(summary = "一键推荐全部并确认（异常行挂起不阻断）")
    public R<Map<String, Object>> recommendAll() {
        return R.ok(service.recommendAll());
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "确认分配（STOCK 来源同时执行上架移位）")
    public R<Void> confirm(@PathVariable String id) {
        service.confirm(id);
        return R.ok(null);
    }

    @PostMapping("/assign")
    @Operation(summary = "指定/改派（合规复检不豁免，违规 422）")
    public R<Void> assign(@RequestParam String grNo,
                          @RequestParam Integer lineNo,
                          @RequestParam String binCode) {
        service.assign(grNo, lineNo, binCode);
        return R.ok(null);
    }

    @GetMapping("/unassigned-stock")
    @Operation(summary = "Tab B：未分配（BIN_CODE=''）库存行")
    public R<Map<String, Object>> unassigned(@RequestParam(required = false) String keyword,
                                             @RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.unassignedStock(keyword, current, size));
    }

    @GetMapping("/putaway-candidates")
    @Operation(summary = "Tab B：某未分配行的上架候选 Top3")
    public R<List<Map<String, Object>>> putawayCandidates(@RequestParam String stockId) {
        return R.ok(service.unassignedCandidates(stockId));
    }

    @PostMapping("/recommend-putaway")
    @Operation(summary = "Tab B：推荐上架（RECOMMENDED；无候选挂起+通知）")
    public R<Map<String, Object>> recommendPutaway(@RequestParam String stockId) {
        return R.ok(service.recommendPutaway(stockId));
    }

    @GetMapping("/history")
    @Operation(summary = "台账历史（含被替代记录，全留痕）")
    public R<List<Map<String, Object>>> history(@RequestParam String sourceDocNo) {
        return R.ok(service.history(sourceDocNo));
    }
}
