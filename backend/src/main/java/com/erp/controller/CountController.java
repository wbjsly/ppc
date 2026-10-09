package com.erp.controller;

import com.erp.common.R;
import com.erp.service.inv.CountDiffService;
import com.erp.service.inv.CountInputService;
import com.erp.service.inv.CountTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 盘点管理（4.11，spec count-management 菜单权限）：
 * 4.11.1/4.11.2 任务生成与录入（写口服务层 WAREHOUSE/ADMIN 强制）、
 * 4.11.3 差异台账与监控清单（COUNT 视图）；审批签署复用 /api/qms/approvals。
 */
@Tag(name = "库存管理-盘点")
@RestController
@RequestMapping("/api/inv/count")
public class CountController {

    private final CountTaskService taskService;
    private final CountInputService inputService;
    private final CountDiffService diffService;

    public CountController(CountTaskService taskService, CountInputService inputService,
                           CountDiffService diffService) {
        this.taskService = taskService;
        this.inputService = inputService;
        this.diffService = diffService;
    }

    // ---------- 4.11.1 周期盘点 ----------

    @PostMapping("/tasks/cycle")
    @Operation(summary = "创建周期盘点任务（手工选范围，WAREHOUSE/ADMIN）")
    public R<Map<String, Object>> createCycle(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> binCodes = (List<String>) body.get("binCodes");
        return R.ok(taskService.createCycle(str(body.get("warehouseCode")), binCodes,
                str(body.get("itemCode")), str(body.get("remark"))));
    }

    // ---------- 4.11.2 全面盘点 ----------

    @PostMapping("/tasks/full")
    @Operation(summary = "创建全面盘点任务（一键全仓，WAREHOUSE/ADMIN）")
    public R<Map<String, Object>> createFull(@RequestBody Map<String, Object> body) {
        return R.ok(taskService.createFull(str(body.get("warehouseCode")),
                str(body.get("remark"))));
    }

    // ---------- 任务通用 ----------

    @GetMapping("/tasks")
    @Operation(summary = "任务分页（状态/类型/仓库筛选）")
    public R<Map<String, Object>> page(@RequestParam(required = false) String status,
                                       @RequestParam(required = false) String taskType,
                                       @RequestParam(required = false) String warehouseCode,
                                       @RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "20") long size) {
        return R.ok(taskService.page(status, taskType, warehouseCode, current, size));
    }

    @GetMapping("/tasks/{id}")
    @Operation(summary = "任务详情（含行状态汇总与 DONE 报告）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(taskService.detail(id));
    }

    @GetMapping("/tasks/{id}/lines")
    @Operation(summary = "任务行列表（未录行无账面列=录入遮蔽）")
    public R<List<Map<String, Object>>> lines(@PathVariable String id,
                                              @RequestParam(required = false) String countStatus,
                                              @RequestParam(required = false) String keyword) {
        return R.ok(taskService.lines(id, countStatus, keyword));
    }

    @PostMapping("/tasks/{id}/cancel")
    @Operation(summary = "取消任务（仅 COUNTING 且零录入，WAREHOUSE/ADMIN）")
    public R<Map<String, Object>> cancel(@PathVariable String id,
                                         @RequestParam String reason) {
        return R.ok(taskService.cancel(id, reason));
    }

    // ---------- 录入与回显 ----------

    @PostMapping("/lines/{lineId}/count")
    @Operation(summary = "提交实盘（>10% 阻断待复盘 / ≤容差自动调整 / 超容差挂审批）",
            description = "响应 = 回显（账面与差异——提交后可见）")
    public R<Map<String, Object>> submitCount(@PathVariable String lineId,
                                              @RequestBody Map<String, Object> body) {
        Object q = body.get("actualQty");
        BigDecimal actualQty = q == null ? null : new BigDecimal(String.valueOf(q));
        return R.ok(inputService.submitCount(lineId, actualQty,
                str(body.get("abnormalFlag")), str(body.get("remark"))));
    }

    @GetMapping("/lines/{lineId}/result")
    @Operation(summary = "录入回显（账面/差异三列）")
    public R<Map<String, Object>> result(@PathVariable String lineId) {
        return R.ok(inputService.result(lineId));
    }

    // ---------- 4.11.3 差异审批台账侧 ----------

    @GetMapping("/diffs")
    @Operation(summary = "COUNT 差异台账（与 PICK 视图隔离）")
    public R<Map<String, Object>> diffs(@RequestParam(required = false) String status,
                                        @RequestParam(required = false) String taskNo,
                                        @RequestParam(required = false) String itemCode,
                                        @RequestParam(defaultValue = "1") long current,
                                        @RequestParam(defaultValue = "20") long size) {
        return R.ok(diffService.page(status, taskNo, itemCode, current, size));
    }

    @GetMapping("/watch-list")
    @Operation(summary = "重点监控仓位清单（同仓位累计 ≥2 次超容差，BR-4.4-41）")
    public R<List<Map<String, Object>>> watchList() {
        return R.ok(diffService.watchList());
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
