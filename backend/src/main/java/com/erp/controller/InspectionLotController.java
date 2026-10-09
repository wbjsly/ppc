package com.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.dao.qms.InspectionLotDao;
import com.erp.entity.qms.InspectionLot;
import com.erp.service.qms.InspectionLotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 检验批（2.5.1 / 6.2.1，/api/qms/lots）。
 * 本控制器先落「查询 + BLOCKED 激活」（group 4）；录入/判定/放行（group 5）随后追加。
 */
@Tag(name = "检验批")
@RestController
@RequestMapping("/api/qms/lots")
public class InspectionLotController {

    private final InspectionLotDao lotDao;
    private final com.erp.dao.qms.LotItemDao lotItemDao;
    private final InspectionLotService service;

    public InspectionLotController(InspectionLotDao lotDao,
                                   com.erp.dao.qms.LotItemDao lotItemDao,
                                   InspectionLotService service) {
        this.lotDao = lotDao;
        this.lotItemDao = lotItemDao;
        this.service = service;
    }

    @Operation(summary = "检验批分页（状态/类型/物料筛选）")
    @GetMapping
    public R<Page<InspectionLot>> page(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "10") long size,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String lotType,
                                       @RequestParam(required = false) String itemCode,
                                       @RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<InspectionLot> qw = new LambdaQueryWrapper<InspectionLot>()
                .eq(hasText(status), InspectionLot::getStatus, status)
                .eq(hasText(lotType), InspectionLot::getLotType, lotType)
                .eq(hasText(itemCode), InspectionLot::getItemCode, itemCode)
                .and(hasText(keyword), w -> w.like(InspectionLot::getLotNo, keyword)
                        .or().like(InspectionLot::getItemName, keyword)
                        .or().like(InspectionLot::getSupplierName, keyword))
                .orderByDesc(InspectionLot::getCreateDate);
        return R.ok(lotDao.selectPage(new Page<>(current, size), qw));
    }

    @Operation(summary = "检验批详情（含检验项快照与录入结果）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        InspectionLot lot = lotDao.selectById(id);
        if (lot == null) {
            throw new ServiceException(404, "检验批不存在");
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("lot", lot);
        out.put("items", lotItemDao.selectList(new LambdaQueryWrapper<com.erp.entity.qms.LotItem>()
                .eq(com.erp.entity.qms.LotItem::getLotId, id)
                .orderByAsc(com.erp.entity.qms.LotItem::getSequenceNo)));
        return R.ok(out);
    }

    @Operation(summary = "BLOCKED 批次激活（标准发布后转待检，BR-4.12-07 例外口）")
    @PostMapping("/{id}/activate")
    public R<InspectionLot> activate(@PathVariable String id) {
        return R.ok(service.activate(id));
    }

    @Operation(summary = "检验录入（CTQ 必录实测值；器具校准卡控；异常二次确认 L4）")
    @SuppressWarnings("unchecked")
    @PostMapping("/{id}/input")
    public R<Map<String, Object>> input(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Object items = body.get("items");
        boolean confirm = Boolean.TRUE.equals(body.get("abnormalConfirm"));
        return R.ok(service.inputItems(id, (List<Map<String, Object>>) items, confirm));
    }

    @Operation(summary = "自动判定（CTQ 不合格/≥Re 不合格；=Ac 边界复核）")
    @PostMapping("/{id}/judge")
    public R<Map<String, Object>> judge(@PathVariable String id) {
        return R.ok(service.judge(id));
    }

    @Operation(summary = "边界判定复核（质量工程师，L2）")
    @PostMapping("/{id}/review")
    public R<Map<String, Object>> review(@PathVariable String id, @RequestBody Map<String, Object> body) {
        boolean pass = !Boolean.FALSE.equals(body.get("pass"));
        String opinion = body.get("opinion") == null ? null : String.valueOf(body.get("opinion"));
        return R.ok(service.reviewBoundary(id, pass, opinion));
    }

    @Operation(summary = "质检员确认合格放行（前置校验 C-4.12-06，不可撤回）")
    @PostMapping("/{id}/release")
    public R<Map<String, Object>> release(@PathVariable String id) {
        return R.ok(service.confirmRelease(id));
    }

    @Operation(summary = "待检看板（数据源 = 检验批，D1 起算点 = 登记提交时间）")
    @GetMapping("/board")
    public R<Page<Map<String, Object>>> board(@RequestParam(defaultValue = "1") long current,
                                              @RequestParam(defaultValue = "10") long size,
                                              @RequestParam(required = false) String status) {
        return R.ok(service.board(current, size, status));
    }

    @Operation(summary = "手工创建 IPQC/OQC 检验批（偏差 D5：工单/发货触发挂桩）")
    @PostMapping
    public R<InspectionLot> createManual(@RequestBody Map<String, Object> body) {
        return R.ok(service.createManual(body));
    }

    @Operation(summary = "严格度记录（加严/解除历史，物料+供应商维度）")
    @GetMapping("/strictness")
    public R<List<com.erp.entity.qms.Strictness>> strictness(
            @RequestParam(required = false) String materialCode,
            @RequestParam(required = false) String supplierId) {
        return R.ok(service.strictnessList(materialCode, supplierId));
    }

    @Operation(summary = "巡检计划列表")
    @GetMapping("/patrol-plans")
    public R<List<com.erp.entity.qms.PatrolPlan>> patrolPlans() {
        return R.ok(service.patrolPlans());
    }

    @Operation(summary = "新建巡检计划（到点自动生成 IPQC 批）")
    @PostMapping("/patrol-plans")
    public R<com.erp.entity.qms.PatrolPlan> createPatrolPlan(@RequestBody Map<String, Object> body) {
        return R.ok(service.createPatrolPlan(body));
    }

    @Operation(summary = "巡检计划启用/暂停切换")
    @PostMapping("/patrol-plans/{id}/toggle")
    public R<com.erp.entity.qms.PatrolPlan> togglePatrolPlan(@PathVariable String id) {
        return R.ok(service.togglePatrolPlan(id));
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
