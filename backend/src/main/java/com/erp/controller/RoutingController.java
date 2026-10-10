package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.mrp.MrpOpWcStandard;
import com.erp.entity.mrp.MrpOperation;
import com.erp.entity.mrp.MrpRouting;
import com.erp.entity.mrp.MrpRoutingOp;
import com.erp.entity.mrp.MrpWorkCenter;
import com.erp.service.mrp.OpWcStandardService;
import com.erp.service.mrp.OperationService;
import com.erp.service.mrp.RoutingService;
import com.erp.service.mrp.WorkCenterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 生产管理-工艺管理（change add-routing-management，菜单 5.2.1~5.2.4，任务 6.1）。
 * 查询仅需认证；写操作权限在服务层 requireAny 分流（接口管可为，菜单管可见，BOM 同范式）；
 * 审批签署复用 /api/qms/approvals（底座节点角色 ROLE_PROCESS_MGR）。
 */
@Tag(name = "工艺管理 Routing")
@RestController
@RequestMapping("/api/mrp")
public class RoutingController {

    private final OperationService operationService;
    private final WorkCenterService workCenterService;
    private final OpWcStandardService standardService;
    private final RoutingService routingService;

    public RoutingController(OperationService operationService,
                             WorkCenterService workCenterService,
                             OpWcStandardService standardService,
                             RoutingService routingService) {
        this.operationService = operationService;
        this.workCenterService = workCenterService;
        this.standardService = standardService;
        this.routingService = routingService;
    }

    /** 路线头 + 有序工序行保存载荷 */
    @Getter
    @Setter
    public static class SaveRequest {
        private MrpRouting head;
        private List<MrpRoutingOp> ops;
    }

    @Getter
    @Setter
    public static class ChangeRequest {
        private String changeReason;
        private boolean upgradeMajor;
    }

    // ---------- 5.2.1 工序维护（工序字典） ----------

    @GetMapping("/operations")
    @Operation(summary = "工序字典列表（关键字/状态筛选）")
    public R<List<Map<String, Object>>> queryOperations(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return R.ok(operationService.query(keyword, status));
    }

    @PostMapping("/operations")
    @Operation(summary = "新增工序（编码建后不可改，全局唯一）")
    public R<MrpOperation> createOperation(@RequestBody MrpOperation in) {
        return R.ok(operationService.create(in));
    }

    @PutMapping("/operations/{id}")
    @Operation(summary = "修改工序（编码不可修改；名称/技能要求/状态可改）")
    public R<MrpOperation> updateOperation(@PathVariable String id, @RequestBody MrpOperation in) {
        in.setId(id);
        return R.ok(operationService.update(in));
    }

    @PostMapping("/operations/{id}/status")
    @Operation(summary = "启用/停用工序（停用不可选入新路线行）")
    public R<MrpOperation> operationStatus(@PathVariable String id, @RequestParam String status) {
        return R.ok(operationService.setStatus(id, status));
    }

    // ---------- 5.2.2 工作中心 ----------

    @GetMapping("/work-centers")
    @Operation(summary = "工作中心台账列表（关键字/状态筛选）")
    public R<List<Map<String, Object>>> queryWorkCenters(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return R.ok(workCenterService.query(keyword, status));
    }

    @PostMapping("/work-centers")
    @Operation(summary = "新增工作中心（外协必须维护供应商；日历工时>0；可用率 0~100）")
    public R<MrpWorkCenter> createWorkCenter(@RequestBody MrpWorkCenter in) {
        return R.ok(workCenterService.create(in));
    }

    @PutMapping("/work-centers/{id}")
    @Operation(summary = "修改工作中心（编码不可修改，校验同新增）")
    public R<MrpWorkCenter> updateWorkCenter(@PathVariable String id, @RequestBody MrpWorkCenter in) {
        in.setId(id);
        return R.ok(workCenterService.update(in));
    }

    @PostMapping("/work-centers/{id}/status")
    @Operation(summary = "启用/停用工作中心（停用不可选入新路线行）")
    public R<MrpWorkCenter> workCenterStatus(@PathVariable String id, @RequestParam String status) {
        return R.ok(workCenterService.setStatus(id, status));
    }

    // ---------- 5.2.3 标准工时（工序×工作中心定额矩阵） ----------

    @GetMapping("/op-wc-standards")
    @Operation(summary = "工时定额列表（opCode/wcCode 级联筛选；组合=适配关系）")
    public R<List<Map<String, Object>>> queryStandards(
            @RequestParam(required = false) String opCode,
            @RequestParam(required = false) String wcCode) {
        return R.ok(standardService.query(opCode, wcCode));
    }

    @PostMapping("/op-wc-standards")
    @Operation(summary = "新增定额（（工序,工作中心）唯一，四类工时≥0）")
    public R<MrpOpWcStandard> createStandard(@RequestBody MrpOpWcStandard in) {
        return R.ok(standardService.create(in));
    }

    @PutMapping("/op-wc-standards/{id}")
    @Operation(summary = "修改定额（编码不可改，仅四类工时可改）")
    public R<MrpOpWcStandard> updateStandard(@PathVariable String id, @RequestBody MrpOpWcStandard in) {
        in.setId(id);
        return R.ok(standardService.update(in));
    }

    @DeleteMapping("/op-wc-standards/{id}")
    @Operation(summary = "删除定额（被已发布路线引用 422）")
    public R<Void> deleteStandard(@PathVariable String id) {
        standardService.delete(id);
        return R.ok();
    }

    // ---------- 5.2.4 路线装配 ----------

    @PostMapping("/routings")
    @Operation(summary = "新建路线草稿（行卡控：工序/工作中心启用 + 定额存在；唯一在途；版本自动）")
    public R<MrpRouting> createRouting(@RequestBody SaveRequest req) {
        return R.ok(routingService.create(req.getHead(), req.getOps()));
    }

    @PutMapping("/routings/{id}")
    @Operation(summary = "覆盖保存路线草稿（仅 DRAFT 可编辑，产品不可改）")
    public R<MrpRouting> saveDraft(@PathVariable String id, @RequestBody SaveRequest req) {
        return R.ok(routingService.saveDraft(id, req.getHead(), req.getOps()));
    }

    @PostMapping("/routings/{id}/change")
    @Operation(summary = "发起变更（仅已发布可发起，变更原因必填，可勾选升级主版本）")
    public R<MrpRouting> change(@PathVariable String id, @RequestBody ChangeRequest req) {
        return R.ok(routingService.change(id, req.getChangeReason(), req.isUpgradeMajor()));
    }

    @PostMapping("/routings/{id}/submit")
    @Operation(summary = "提交审核（挂 RoutingPublish 单节点审批，DRAFT→PENDING，重复提交 422）")
    public R<MrpRouting> submit(@PathVariable String id) {
        return R.ok(routingService.submit(id));
    }

    @PostMapping("/routings/{id}/obsolete")
    @Operation(summary = "手动废止（PROCESS_MGR/ADMIN，PUBLISHED|REVISED→OBSOLETE）")
    public R<Void> obsolete(@PathVariable String id) {
        routingService.obsolete(id);
        return R.ok();
    }

    @GetMapping("/routings/published")
    @Operation(summary = "下游契约：按产品取已发布路线（有序工序行+四类工时+产能三要素；未发布返回空）")
    public R<Map<String, Object>> publishedRoute(@RequestParam String itemCode) {
        return R.ok(routingService.publishedRoute(itemCode));
    }

    @GetMapping("/routings/{id}")
    @Operation(summary = "路线详情（头 + 有序工序行，行带定额与工作中心）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(routingService.detail(id));
    }

    @GetMapping("/routings")
    @Operation(summary = "路线版本列表（状态/产品/关键字；版本历史留痕字段）")
    public R<List<Map<String, Object>>> query(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String itemCode,
            @RequestParam(required = false) String keyword) {
        return R.ok(routingService.query(status, itemCode, keyword));
    }
}
