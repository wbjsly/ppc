package com.erp.controller;

import com.erp.common.R;
import com.erp.service.mrp.KittingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 生产管理-齐套检查（change add-kitting-check，菜单 5.5.1/5.5.2，spec kitting-check）。
 * 5.5.1 校验工作台：单张/勾选批量校验（串行去重，偏差 D2 人工源静默）+ 逐行明细走
 * GET /api/mrp/mos/{id}/kitting-detail（MoController）；5.5.2 聚合看板 + 缺料转采购建议。
 * 写操作权限四角色门在服务层（design D9）；查询仅需认证。
 */
@Tag(name = "齐套检查")
@RestController
@RequestMapping("/api/mrp/kitting")
public class KittingController {

    private final KittingService service;

    public KittingController(KittingService service) {
        this.service = service;
    }

    /** 校验载荷（单张传一个 ID，批量传多个） */
    @Getter
    @Setter
    public static class CheckRequest {
        private List<String> moIds;
    }

    @PostMapping("/checks")
    @Operation(summary = "批量齐套校验（串行 + 批次内去重 + 人工源静默，design D3/D9）")
    public R<List<Map<String, Object>>> check(@RequestBody CheckRequest req) {
        return R.ok(service.checkUser(req.getMoIds()));
    }

    @GetMapping("/shortages")
    @Operation(summary = "缺料聚合看板（5.5.2：按物料 group 缺料工单数/总缺料量/明细下钻）")
    public R<List<Map<String, Object>>> aggregate(
            @RequestParam(required = false) String itemCode,
            @RequestParam(required = false) String moNo) {
        return R.ok(service.aggregate(itemCode, moNo));
    }
}
