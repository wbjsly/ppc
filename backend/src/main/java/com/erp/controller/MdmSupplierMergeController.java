package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.mdm.MdmSupplierMergeLog;
import com.erp.service.mdm.MdmSupplierMergeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 供应商合并去重（供方管理 1.4.2，/api/mdm/supplier-merges） */
@RestController
@RequestMapping("/api/mdm/supplier-merges")
public class MdmSupplierMergeController {

    private final MdmSupplierMergeService service;

    public MdmSupplierMergeController(MdmSupplierMergeService service) {
        this.service = service;
    }

    /** 疑似重复候选（税号 + 名称≤3，排除自身与已合并） */
    @GetMapping("/candidates")
    public R<List<Map<String, Object>>> candidates(@RequestParam(defaultValue = "") String keyword,
                                                   @RequestParam(required = false) String excludeId) {
        return R.ok(service.candidates(keyword, excludeId));
    }

    /** 差异对比并排 + 方向建议 */
    @GetMapping("/compare")
    public R<Map<String, Object>> compare(@RequestParam String sourceId,
                                          @RequestParam String targetId) {
        return R.ok(service.compare(sourceId, targetId));
    }

    /** 影响面：证照计数 + 四类迁移桩 */
    @GetMapping("/impact")
    public R<Map<String, Object>> impact(@RequestParam String sourceId) {
        return R.ok(service.impact(sourceId));
    }

    /** 合并执行（单事务五步） */
    @PostMapping("/merge")
    public R<MdmSupplierMergeLog> merge(@RequestBody Map<String, String> body) {
        return R.ok(service.merge(body.get("sourceId"), body.get("targetId"), body.get("reason")));
    }

    /** 30 天回退 */
    @PostMapping("/revert")
    public R<MdmSupplierMergeLog> revert(@RequestBody Map<String, String> body) {
        return R.ok(service.revert(body.get("logId"), body.get("reason")));
    }

    /** 合并日志分页 */
    @GetMapping
    public R<Map<String, Object>> logPage(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "10") long size,
                                          @RequestParam(defaultValue = "") String keyword) {
        return R.ok(service.logPage(current, size, keyword));
    }
}
