package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoShortage;
import com.erp.service.mrp.MoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Getter;
import lombok.Setter;
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
 * 生产管理-工单管理（change add-work-order-management，菜单 5.4.1~5.4.5，任务 7.1）。
 * 查询仅需认证（服务层 401）；写操作权限在服务层 requireAny 分流（PLANNER/ADMIN）；
 * 审批签署复用 /api/qms/approvals（底座节点角色 ROLE_PLAN_MGR）。
 */
@Tag(name = "生产工单 MO")
@RestController
@RequestMapping("/api/mrp/mos")
public class MoController {

    private final MoService service;

    public MoController(MoService service) {
        this.service = service;
    }

    /** 手工新建 / PMO 建单的头覆盖载荷 */
    @Getter
    @Setter
    public static class CreateRequest {
        private MrpMo head;
        /** PMO 入口：建议行 ID（createFromPmo 用） */
        private String suggestId;
        /** PMO 入口的日期/优先级覆盖（可空） */
        private MrpMo overrides;
    }

    /** 挂起/取消载荷 */
    @Getter
    @Setter
    public static class ReasonRequest {
        private String reason;
    }

    /** 拆分载荷 */
    @Getter
    @Setter
    public static class SplitRequest {
        private List<BigDecimal> childQtys;
        private String reason;
    }

    /** 完工确认载荷 */
    @Getter
    @Setter
    public static class CompleteRequest {
        private BigDecimal qualifiedQty;
    }

    // ---------- 5.4.1 工单创建 ----------

    @GetMapping("/candidate-pmos")
    @Operation(summary = "可选计划工单（PMO 列表：已转正未关联，D6 对接）")
    public R<List<Map<String, Object>>> candidatePmos() {
        return R.ok(service.candidatePmos());
    }

    @PostMapping
    @Operation(summary = "手工新建工单（FR-4.5-3-2~6：头卡控 + 双快照 + 缺料预检）")
    public R<Map<String, Object>> create(@RequestBody CreateRequest req) {
        return R.ok(service.create(req.getHead()));
    }

    @PostMapping("/from-pmo")
    @Operation(summary = "从 PMO 建单（FR-4.5-3-1：带出数据 + MO_NO 幂等回写）")
    public R<Map<String, Object>> createFromPmo(@RequestBody CreateRequest req) {
        return R.ok(service.createFromPmo(req.getSuggestId(), req.getOverrides()));
    }

    @GetMapping
    @Operation(summary = "工单列表（状态/产品/缺料过滤，含全部留痕字段）")
    public R<List<MrpMo>> list(@RequestParam(required = false) String status,
                               @RequestParam(required = false) String productCode,
                               @RequestParam(required = false) String shortageFlag) {
        return R.ok(service.list(status, productCode, shortageFlag));
    }

    @GetMapping("/{id}")
    @Operation(summary = "工单详情（头 + BOM 快照 + 工序快照 + 缺料清单）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @GetMapping("/{id}/shortages")
    @Operation(summary = "缺料清单（FR-4.5-3-6，5.4.3 释放页 / 5.5 复用）")
    public R<List<MrpMoShortage>> shortages(@PathVariable String id) {
        return R.ok(service.shortages(id));
    }

    // ---------- 5.4.2 工单审批 ----------

    @PostMapping("/{id}/submit")
    @Operation(summary = "提交审批（PLANNED→PENDING，挂 ApprovalEngine 串行单节点）")
    public R<MrpMo> submit(@PathVariable String id) {
        return R.ok(service.submit(id));
    }

    // ---------- 5.4.3 工单释放 ----------

    @PostMapping("/{id}/release")
    @Operation(summary = "释放工单（CONFIRMED→RELEASED + 重跑齐套打标，缺料不阻断）")
    public R<MrpMo> release(@PathVariable String id) {
        return R.ok(service.release(id));
    }

    // ---------- 5.4.4 工单变更 ----------

    @PostMapping("/{id}/hold")
    @Operation(summary = "挂起（RELEASED→HOLD，原因必填）")
    public R<MrpMo> hold(@PathVariable String id, @RequestBody ReasonRequest req) {
        return R.ok(service.hold(id, req.getReason()));
    }

    @PostMapping("/{id}/resume")
    @Operation(summary = "挂起恢复（HOLD→RELEASED）")
    public R<MrpMo> resume(@PathVariable String id) {
        return R.ok(service.resume(id));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "取消（原因必填，终态）")
    public R<MrpMo> cancel(@PathVariable String id, @RequestBody ReasonRequest req) {
        return R.ok(service.cancel(id, req.getReason()));
    }

    @PostMapping("/{id}/split")
    @Operation(summary = "拆分（子单合计=剩余量 L1，C-4.5-15；继承快照与交期）")
    public R<Map<String, Object>> split(@PathVariable String id, @RequestBody SplitRequest req) {
        return R.ok(service.split(id, req.getChildQtys(), req.getReason()));
    }

    // ---------- 5.4.5 完工与关闭 ----------

    @PostMapping("/{id}/complete")
    @Operation(summary = "手动完工确认（RELEASED→COMPLETED + 合格产出留痕，5.7 落地后改自动）")
    public R<MrpMo> complete(@PathVariable String id, @RequestBody CompleteRequest req) {
        return R.ok(service.complete(id, req.getQualifiedQty()));
    }

    @GetMapping("/{id}/close-precheck")
    @Operation(summary = "关闭预检（状态硬校验 + MoCloseCheck 钩子结果）")
    public R<Map<String, Object>> closePrecheck(@PathVariable String id) {
        return R.ok(service.precheckClose(id));
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "关闭工单（→CLOSED 终态 + 留痕，钩子不满足 422）")
    public R<MrpMo> close(@PathVariable String id) {
        return R.ok(service.close(id));
    }

    // ---------- 下游消费契约 ----------

    @GetMapping("/in-process")
    @Operation(summary = "在制供给查询（RELEASED/HOLD/未关闭 COMPLETED 剩余量，itemCodes 逗号分隔，供 MRP InProcess 桩）")
    public R<Map<String, BigDecimal>> inProcess(@RequestParam(value = "itemCodes", required = false) String itemCodes) {
        java.util.Set<String> codes = new java.util.LinkedHashSet<>();
        if (itemCodes != null && !itemCodes.trim().isEmpty()) {
            for (String c : itemCodes.split(",")) {
                if (!c.trim().isEmpty()) {
                    codes.add(c.trim());
                }
            }
        }
        return R.ok(service.inProcess(codes));
    }
}
