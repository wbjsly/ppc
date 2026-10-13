package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoBom;
import com.erp.service.mrp.MoService;
import com.erp.service.mrp.ReworkOrderService;
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

import java.util.List;
import java.util.Map;

/**
 * 返工返修 5.8（change add-rework-management，spec rework-order / rework-execution）。
 * 5.8.1 计划侧：判定来源选入建单/返工列表/阶段；5.8.2 质量侧：二次返工审批、
 * 强制报废、复检合格入库重试。权限校验在服务层（spec 角色即服务门）。
 */
@Tag(name = "5.8 返工返修")
@RestController
@RequestMapping("/api/mrp/rework")
public class ReworkController {

    private final ReworkOrderService service;
    private final MoService moService;

    public ReworkController(ReworkOrderService service, MoService moService) {
        this.service = service;
        this.moService = moService;
    }

    @Getter
    @Setter
    public static class CreateRequest {
        private MrpMo head;
        private List<MrpMoBom> bom;
    }

    @GetMapping("/sources")
    @Operation(summary = "判定来源候选（NCR 处置=返工 / 退货判定=可返修，未关联返工工单）")
    public R<List<Map<String, Object>>> sources(@RequestParam String srcType) {
        return R.ok(service.sourceCandidates(srcType));
    }

    @PostMapping("/create")
    @Operation(summary = "创建返工工单（来源校验→C-4.5-13 卡控→选路→转在制→BOM 快照）")
    public R<MrpMo> create(@RequestBody CreateRequest req) {
        return R.ok(service.create(req.getHead(), req.getBom()));
    }

    @GetMapping("/list")
    @Operation(summary = "返工工单列表（RW 分池，计划/质量只读）")
    public R<List<MrpMo>> list(@RequestParam(required = false) String status,
                               @RequestParam(required = false) String productCode) {
        return R.ok(service.list(status, productCode));
    }

    @GetMapping("/phase/{moId}")
    @Operation(summary = "阶段推导（PENDING_INSP / PENDING_STOCK_IN / CLOSED）")
    public R<String> phase(@PathVariable String moId) {
        return R.ok(service.phaseOf(moId));
    }

    @PostMapping("/second")
    @Operation(summary = "发起二次返工审批（质量侧，ROLE_QUALITY_MGR 签，bizId=判定单号）")
    public R<Map<String, Object>> second(@RequestBody Map<String, String> body) {
        return R.ok(service.applySecond(body.get("srcNo")));
    }

    @PostMapping("/scrap")
    @Operation(summary = "强制报废执行（BR-4.5-02：第二次返工检验不合格终局）")
    public R<Void> scrap(@RequestBody Map<String, String> body) {
        service.enforceScrap(body.get("moId"));
        return R.ok();
    }

    @GetMapping("/cost/{moId}")
    @Operation(summary = "返工成本归集明细（COPQ REWORK 行；5.8.2 展示与经济性提示）")
    public R<Map<String, Object>> cost(@PathVariable String moId) {
        return R.ok(service.costOf(moId));
    }

    @PostMapping("/stock-in")
    @Operation(summary = "复检合格入库解冻关单（待入库重试入口，幂等）")
    public R<Void> stockIn(@RequestBody Map<String, String> body) {
        service.closeFromInspection(body.get("moId"));
        return R.ok();
    }
}
