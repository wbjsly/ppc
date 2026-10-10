package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.mrp.MrpBom;
import com.erp.entity.mrp.MrpBomItem;
import com.erp.service.mrp.BomService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Getter;
import lombok.Setter;
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
 * 生产管理-物料清单（change add-bom-management，菜单 5.1.1~5.1.4，任务 6.1）。
 * 查询仅需认证；写操作权限在服务层 requireAny 分流（接口管可为，菜单管可见，freeze 同范式）；
 * 审批签署复用 /api/qms/approvals（底座节点角色 ROLE_PROCESS_MGR）。
 */
@Tag(name = "物料清单 BOM")
@RestController
@RequestMapping("/api/mrp/boms")
public class BomController {

    private final BomService service;

    public BomController(BomService service) {
        this.service = service;
    }

    /** 头 + 行（行携带行级替代 substitutes）保存载荷 */
    @Getter
    @Setter
    public static class SaveRequest {
        private MrpBom head;
        private List<MrpBomItem> items;
    }

    @Getter
    @Setter
    public static class ChangeRequest {
        private String changeReason;
        private boolean upgradeMajor;
    }

    // ---------- 5.1.1 清单创建 / 5.1.2 清单变更 ----------

    @PostMapping
    @Operation(summary = "新建 BOM 草稿（FR-4.5-1-1/2/3：父项行校验、行级替代、内嵌循环校验）")
    public R<MrpBom> create(@RequestBody SaveRequest req) {
        return R.ok(service.create(req.getHead(), req.getItems()));
    }

    @PutMapping("/{id}")
    @Operation(summary = "覆盖保存草稿（仅 DRAFT 可编辑，父项不可改）")
    public R<MrpBom> saveDraft(@PathVariable String id, @RequestBody SaveRequest req) {
        return R.ok(service.saveDraft(id, req.getHead(), req.getItems()));
    }

    @PostMapping("/{id}/copy")
    @Operation(summary = "复制版本为独立草稿（copy_from_id 留痕，版本号重分配）")
    public R<MrpBom> copy(@PathVariable String id) {
        return R.ok(service.copy(id));
    }

    @PostMapping("/{id}/change")
    @Operation(summary = "发起变更（仅已发布可发起，变更原因必填，可勾选升级主版本）")
    public R<MrpBom> change(@PathVariable String id, @RequestBody ChangeRequest req) {
        return R.ok(service.change(id, req.getChangeReason(), req.isUpgradeMajor()));
    }

    // ---------- 5.1.3 版本发布 ----------

    @PostMapping("/{id}/submit")
    @Operation(summary = "提交审核（挂 BomPublish 单节点审批，DRAFT→PENDING，重复提交 422）")
    public R<MrpBom> submit(@PathVariable String id) {
        return R.ok(service.submit(id));
    }

    @PostMapping("/{id}/obsolete")
    @Operation(summary = "手动废止（PROCESS_MGR/ADMIN，PUBLISHED|REVISED→OBSOLETE）")
    public R<Void> obsolete(@PathVariable String id) {
        service.obsolete(id);
        return R.ok();
    }

    // ---------- 5.1.4 循环校验 + 查询 ----------

    @GetMapping("/scan")
    @Operation(summary = "循环校验扫描（parentItemCode 空=全量；返回环路径/超限提示报告）")
    public R<Map<String, Object>> scan(@RequestParam(required = false) String parentItemCode) {
        return R.ok(service.scan(parentItemCode));
    }

    @GetMapping("/substitute-candidates")
    @Operation(summary = "行替代候选带出（读取 MDM 物料替代关系，只读引用）")
    public R<List<Map<String, Object>>> substituteCandidates(@RequestParam String itemCode) {
        return R.ok(service.substituteCandidates(itemCode));
    }

    @GetMapping("/{id}")
    @Operation(summary = "版本详情（头 + 行 + 行级替代）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @GetMapping
    @Operation(summary = "版本列表（状态/父项/关键字；版本历史留痕：变更原因/来源版本/发布人/发布时间/驳回意见）")
    public R<List<Map<String, Object>>> query(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String parentItemCode,
            @RequestParam(required = false) String keyword) {
        return R.ok(service.query(status, parentItemCode, keyword));
    }
}
