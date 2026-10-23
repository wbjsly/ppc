package com.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.dao.eam.EamDisposalLineDao;
import com.erp.dao.eam.EamDisposalOrderDao;
import com.erp.dao.eam.EamDisposalRecycleDao;
import com.erp.entity.eam.EamDisposalLine;
import com.erp.entity.eam.EamDisposalOrder;
import com.erp.entity.eam.EamDisposalRecycle;
import com.erp.service.eam.EamDisposalService;
import com.erp.service.eam.EamTransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资产处置 Controller（add-asset-disposal，菜单 7.6.1~7.6.3）。
 */
@Tag(name = "资产处置")
@RestController
@RequestMapping("/api/eam/disposals")
@RequiredArgsConstructor
public class EamDisposalController {

    private final EamDisposalService service;
    private final EamTransferService transferService;
    private final EamDisposalOrderDao orderDao;
    private final EamDisposalLineDao lineDao;
    private final EamDisposalRecycleDao recycleDao;

    @Operation(summary = "处置单分页")
    @GetMapping
    public R<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "20") long size,
                                             @RequestParam(required = false) String type,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<EamDisposalOrder> qw = new LambdaQueryWrapper<EamDisposalOrder>()
                .eq(EamDisposalOrder::getDelFlag, "0")
                .eq(type != null && !type.isBlank(), EamDisposalOrder::getDisposalType, type)
                .eq(status != null && !status.isBlank(), EamDisposalOrder::getAppStatus, status)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(EamDisposalOrder::getDspNo, keyword)
                        .or().like(EamDisposalOrder::getReasonNote, keyword))
                .orderByDesc(EamDisposalOrder::getCreateDate);
        Page<EamDisposalOrder> page = orderDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> out = new Page<>(page.getCurrent(), page.getTotal());
        out.setRecords(page.getRecords().stream().map(o -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.getId());
            m.put("dspNo", o.getDspNo());
            m.put("disposalType", o.getDisposalType());
            m.put("appStatus", o.getAppStatus());
            m.put("totalCost", o.getTotalCost());
            m.put("totalNbv", o.getTotalNbv());
            m.put("gainLoss", o.getGainLoss());
            m.put("receivableBal", o.getReceivableBal());
            m.put("createDate", o.getCreateDate());
            return m;
        }).toList());
        return R.ok(out);
    }

    @Operation(summary = "处置单明细（单/行/回收）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order", orderDao.selectById(id));
        out.put("lines", lineDao.selectList(new LambdaQueryWrapper<EamDisposalLine>()
                .eq(EamDisposalLine::getOrderId, id)));
        out.put("recycles", recycleDao.selectList(new LambdaQueryWrapper<EamDisposalRecycle>()
                .eq(EamDisposalRecycle::getOrderId, id)));
        return R.ok(out);
    }

    @Operation(summary = "创建处置单（批量一单多卡）")
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody DisposalPayload payload) {
        return R.ok(service.create(payload.order(), payload.lines(), payload.recycles()));
    }

    @Operation(summary = "编辑草稿")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable String id, @RequestBody DisposalPayload payload) {
        EamDisposalOrder order = payload.order();
        order.setId(id);
        service.update(order, payload.lines(), payload.recycles());
        return R.ok();
    }

    /** 强类型请求体（Jackson 直反序列化，避免 Map 强转异常） */
    public record DisposalPayload(EamDisposalOrder order,
                                  List<EamDisposalLine> lines,
                                  List<EamDisposalRecycle> recycles) {
        public List<EamDisposalLine> lines() {
            return lines == null ? List.of() : lines;
        }

        public List<EamDisposalRecycle> recycles() {
            return recycles == null ? List.of() : recycles;
        }
    }

    @Operation(summary = "提交审批（C-05 复核 + BR-31 报告 + 分级链）")
    @PostMapping("/{id}/submit")
    public R<Map<String, Object>> submit(@PathVariable String id) {
        return R.ok(service.submit(id));
    }

    @Operation(summary = "作废（仅草稿/驳回）")
    @PostMapping("/{id}/void")
    public R<Void> voidOrder(@PathVariable String id,
                             @RequestParam(required = false) String reason) {
        service.voidOrder(id, reason);
        return R.ok();
    }

    @Operation(summary = "执行确认（BR-33/32/36 校验 + 回收入库 + 段2/3 凭证 + 卡片 DISPOSED）")
    @PostMapping("/{id}/exec")
    public R<Map<String, Object>> exec(@PathVariable String id,
                                       @RequestParam String proofNo,
                                       @RequestParam(required = false) String envAssessNo,
                                       @RequestParam(required = false) String dataDestroyNo) {
        return R.ok(service.exec(id, proofNo, envAssessNo, dataDestroyNo));
    }

    @Operation(summary = "收款登记（BR-35 冲减应收）")
    @PostMapping("/{id}/receive")
    public R<Map<String, Object>> receive(@PathVariable String id,
                                          @RequestParam BigDecimal amount,
                                          @RequestParam(required = false) String note) {
        return R.ok(service.receive(id, amount, note));
    }

    @Operation(summary = "结案报告提交（应收/入库/卡片条件校验 → 单签归档）")
    @PostMapping("/{id}/submit-report")
    public R<Map<String, Object>> submitReport(@PathVariable String id) {
        return R.ok(service.submitReport(id));
    }

    @Operation(summary = "整单冲回（结案前；库存入库单不自动冲销）")
    @PostMapping("/{id}/reverse")
    public R<Map<String, Object>> reverse(@PathVariable String id) {
        return R.ok(service.reverse(id));
    }

    @Operation(summary = "闲置池直达预填（24 月 DISPOSAL_EVAL 数据源，复用调拨共享池）")
    @GetMapping("/idle-pool")
    public R<List<Map<String, Object>>> idlePool() {
        return R.ok(transferService.idlePool());
    }

}
