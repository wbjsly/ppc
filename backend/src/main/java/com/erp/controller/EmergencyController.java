package com.erp.controller;

import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.service.proc.EmergencyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 紧急采购（2.1.3，/api/proc/emergency*）。
 * SecurityConfig：POST/PUT/DELETE /api/proc/** → ADMIN（含通道恢复）。
 */
@RestController
@RequestMapping("/api/proc")
public class EmergencyController {

    private final EmergencyService service;

    public EmergencyController(EmergencyService service) {
        this.service = service;
    }

    @GetMapping("/emergency")
    public R<Map<String, Object>> page(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "10") long size,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String keyword) {
        var page = service.page(current, size, status, keyword);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("records", page.getRecords());
        result.put("total", page.getTotal());
        return R.ok(result);
    }

    @GetMapping("/emergency/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    /** PR 候选（已批准/待询价） */
    @GetMapping("/emergency/pr-candidates")
    public R<Map<String, Object>> prCandidates(
            @RequestParam(required = false) String keyword) {
        return R.ok(service.prCandidates(keyword));
    }

    /** RFQ/PO 放行桩（BR-4.2-11 消费预留） */
    @GetMapping("/emergency/clearance")
    public R<Map<String, Object>> clearance(@RequestParam String prNo) {
        return R.ok(service.clearance(prNo));
    }

    /** 发起紧急申请（通道/PR/防重三重前置） */
    @PostMapping("/emergency")
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        return R.ok(service.create(payload));
    }

    /** 采购总监特批通过 */
    @PostMapping("/emergency/{id}/approve")
    public R<Map<String, Object>> approve(@PathVariable String id,
                                          @RequestBody Map<String, Object> body) {
        return R.ok(service.approve(id, str(body.get("reason"))));
    }

    /** 特批驳回 */
    @PostMapping("/emergency/{id}/reject")
    public R<Map<String, Object>> reject(@PathVariable String id,
                                         @RequestBody Map<String, Object> body) {
        return R.ok(service.reject(id, str(body.get("reason"))));
    }

    /** 比价补齐登记 */
    @PostMapping("/emergency/{id}/fill")
    public R<Map<String, Object>> fill(@PathVariable String id,
                                       @RequestBody Map<String, Object> body) {
        return R.ok(service.fill(id, body));
    }

    /** 人工关闭（原因必填） */
    @PostMapping("/emergency/{id}/close")
    public R<Void> close(@PathVariable String id, @RequestBody Map<String, Object> body) {
        service.close(id, str(body.get("reason")));
        return R.ok();
    }

    // ---------- 通道台账 ----------

    @GetMapping("/emergency-channels")
    public R<Map<String, Object>> channels(@RequestParam(defaultValue = "1") long current,
                                           @RequestParam(defaultValue = "10") long size,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String keyword) {
        return R.ok(service.channels(current, size, status, keyword));
    }

    /** 总监复核恢复（OPEN 422；恢复 + 关全部逾期例外） */
    @PostMapping("/emergency-channels/restore")
    public R<Map<String, Object>> restore(@RequestBody Map<String, Object> body) {
        return R.ok(service.restoreChannel(str(body.get("account")), str(body.get("note"))));
    }

    private String str(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            throw new ServiceException(422, "参数必填");
        }
        return String.valueOf(o);
    }
}
