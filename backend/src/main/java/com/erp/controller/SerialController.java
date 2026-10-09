package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.inv.InvSerial;
import com.erp.service.inv.SerialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * 序列号台账（4.2.2，spec serial-master）。
 * 写权限：ROLE_WAREHOUSE,ROLE_ADMIN（服务层二次校验）；check 判重仅需认证（D4 供写入点复用）。
 */
@Tag(name = "序列台账")
@RestController
@RequestMapping("/api/inv/serials")
public class SerialController {

    private final SerialService service;

    public SerialController(SerialService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "序列查询", description = "序列号/物料/批次/状态筛选，创建时间倒序")
    public R<List<InvSerial>> query(@RequestParam(required = false) String serialNo,
                                    @RequestParam(required = false) String itemCode,
                                    @RequestParam(required = false) String batchNo,
                                    @RequestParam(required = false) String status) {
        return R.ok(service.query(serialNo, itemCode, batchNo, status));
    }

    @GetMapping("/check")
    @Operation(summary = "序列判重查询", description = "BR-4.11-15 口径预留：仅需认证、只读，返回 exists/status/batchNo/locationRemark")
    public R<Map<String, Object>> check(@RequestParam(required = false) String itemCode,
                                        @RequestParam String serialNo) {
        return R.ok(service.check(itemCode, serialNo));
    }

    @GetMapping("/{id}")
    @Operation(summary = "序列详情")
    public R<InvSerial> get(@PathVariable String id) {
        return R.ok(service.get(id));
    }

    @GetMapping("/{id}/logs")
    @Operation(summary = "流转记录", description = "前后状态/原因/操作人/时间，倒序")
    public R<List<Map<String, Object>>> logs(@PathVariable String id) {
        return R.ok(service.logs(id));
    }

    @PostMapping
    @Operation(summary = "新建序列", description = "手工录入、全局唯一 409（串码）、初始状态 IN_STOCK")
    public R<InvSerial> create(@RequestBody InvSerial serial) {
        return R.ok(service.create(serial));
    }

    @org.springframework.web.bind.annotation.PutMapping
    @Operation(summary = "变更序列备注", description = "序列号/物料锁定（422）；状态只经流转端点变更")
    public R<InvSerial> update(@RequestBody InvSerial serial) {
        return R.ok(service.update(serial));
    }

    @PostMapping("/{id}/transition")
    @Operation(summary = "状态流转", description = "白名单合法边：IN_STOCK→OUT/FROZEN/SCRAPPED、FROZEN→IN_STOCK（解冻原因必填）；终态不可回退 422；流转留痕")
    public R<InvSerial> transition(@PathVariable String id,
                                   @RequestParam String toStatus,
                                   @RequestParam(required = false) String reason,
                                   @RequestParam(required = false) String locationRemark) {
        return R.ok(service.transition(id, toStatus, reason, locationRemark));
    }
}
