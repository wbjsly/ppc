package com.erp.controller;

import com.erp.common.R;
import com.erp.service.iot.IotScanWorkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 扫码作业五场景入口（add-iot-mobile 13.2，FR-4.11-2-x 薄适配）。
 * 复用 PROC/INV/SD 服务层校验不改本体；移动端异常码映射（L1/L2 提示语）在服务层。
 */
@Tag(name = "移动物联-扫码作业")
@RestController
@RequestMapping("/api/iot/scan")
public class ScanWorkController {

    private final IotScanWorkService service;

    public ScanWorkController(IotScanWorkService service) {
        this.service = service;
    }

    @PostMapping("/receive")
    @Operation(summary = "13.2.1 收货扫码（RFID 损坏/重复拒收 C-4.11-02 + 转收货登记）")
    public R<Map<String, Object>> receive(@RequestBody Map<String, Object> payload) {
        return R.ok(service.receive(payload));
    }

    @PostMapping("/putaway")
    @Operation(summary = "13.2.2 上架扫码（货位合规/容量校验 + 备选推荐）")
    public R<Map<String, Object>> putaway(@RequestBody Map<String, Object> payload) {
        return R.ok(service.putaway(payload));
    }

    @PostMapping("/pick")
    @Operation(summary = "13.2.3 拣货扫码（三码校验 + 批次不一致冻结货位 + 通知主管）")
    public R<Map<String, Object>> pick(@RequestBody Map<String, Object> payload) {
        return R.ok(service.pickScan(payload));
    }

    @PostMapping("/ship-review")
    @Operation(summary = "13.2.4 发货扫码复核（物料/批次/数量比清单，不一致 L1 停发）")
    public R<Map<String, Object>> shipReview(@RequestBody Map<String, Object> payload) {
        return R.ok(service.shipReview(payload));
    }

    @PostMapping("/count")
    @Operation(summary = "13.2.5 盘点扫码（实盘录入 + 盘亏 L2 审批提示映射）")
    public R<Map<String, Object>> count(@RequestBody Map<String, Object> payload) {
        return R.ok(service.countScan(payload));
    }
}
