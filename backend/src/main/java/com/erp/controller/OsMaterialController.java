package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.mrp.OsMaterial;
import com.erp.service.mrp.OsMaterialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 外协甲供发料单接口（change add-outsourcing-processing，spec outsourcing-material-supply，菜单 5.10.3）。
 * 权限：读/写 = PLANNER/PLAN_MGR/PURCHASER/ADMIN（service 层 requireRole 校验，spec 菜单权限 5.10.3）。
 * 状态机：DRAFT（可改可删）→ POSTED（已出库）→ RETURNED（余料已退终态）。
 */
@Tag(name = "外协发料（5.10.3）")
@RestController
@RequestMapping("/api/mrp/os-material")
public class OsMaterialController {

    private final OsMaterialService service;

    public OsMaterialController(OsMaterialService service) {
        this.service = service;
    }

    @GetMapping("/list")
    @Operation(summary = "发料单列表", description = "派工单号/供应商/状态筛选，创建时间倒序")
    public R<List<OsMaterial>> list(@RequestParam(required = false) String dispatchNo,
                                    @RequestParam(required = false) String supplierCode,
                                    @RequestParam(required = false) String status) {
        return R.ok(service.list(dispatchNo, supplierCode, status));
    }

    @GetMapping("/{id}")
    @Operation(summary = "发料单详情", description = "头 + 行（行含实发/定额冲减/余料退回三累计，可退余量=实发−冲减−退回）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @PostMapping
    @Operation(summary = "创建发料单（DRAFT）",
            description = "仅甲供（COMPANY_SUPPLY）外协派工单；创建即校验可用库存，不足 422 逐行列缺口")
    public R<OsMaterial> create(@RequestBody Map<String, Object> body) {
        return R.ok(service.create(body));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除草稿", description = "仅 DRAFT 可删；POSTED/RETURNED 422")
    public R<Void> delete(@PathVariable String id) {
        service.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/post")
    @Operation(summary = "出库过账（DRAFT → POSTED）",
            description = "逐行复核可用库存并预扣（OS_MAT_OUT 扣可用 + 在途入池）；不足 422 列出行级缺口")
    public R<OsMaterial> post(@PathVariable String id) {
        return R.ok(service.post(id));
    }

    @PostMapping("/{id}/return")
    @Operation(summary = "余料退回（POSTED → RETURNED）",
            description = "按行指定退回量（≤ 可退余量，超退 422 逐行列缺口）；全退置 RETURNED 终态")
    public R<OsMaterial> returnRemaining(@PathVariable String id,
                                         @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("rows");
        return R.ok(service.returnRemaining(id, rows));
    }
}
