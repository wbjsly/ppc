package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.mdm.MdmCustomerView;
import com.erp.service.mdm.MdmCreditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 信用额度（客户管理 1.3.2，/api/mdm/credit-limits） */
@RestController
@RequestMapping("/api/mdm/credit-limits")
public class MdmCreditController {

    private final MdmCreditService service;

    public MdmCreditController(MdmCreditService service) {
        this.service = service;
    }

    /** 列表概览（含占用率/超期/临时，查询前执行懒校验幂等扫描） */
    @GetMapping("/overview")
    public R<Map<String, Object>> overview(@RequestParam(defaultValue = "") String keyword,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(defaultValue = "1") long current,
                                           @RequestParam(defaultValue = "10") long size) {
        return R.ok(service.overview(keyword, status, current, size));
    }

    /** 法人额度调整（常规求和 422 / 临时有效期校验 / 原因必填） */
    @PostMapping("/view-limit")
    public R<MdmCustomerView> adjustViewLimit(@RequestBody MdmCustomerView view) {
        return R.ok(service.adjustViewLimit(view));
    }

    /** 集团基准调整（总额度/评级，改求和基准） */
    @PutMapping("/group-limit")
    public R<Map<String, Object>> adjustGroupLimit(@RequestBody Map<String, String> payload) {
        return R.ok(service.adjustGroupLimit(payload));
    }

    /** 占用率（BR-4.1-32：>80% over80 标记，未配置 configured=false） */
    @GetMapping("/group-occupancy")
    public R<Map<String, Object>> occupancy(@RequestParam String groupId) {
        return R.ok(service.occupancy(groupId));
    }

    /** 复审通过（C-4.3-13：更新日期 + 清压缩恢复） */
    @PostMapping("/review")
    public R<MdmCustomerView> reviewPassed(@RequestParam String viewId,
                                           @RequestParam(required = false) String reason) {
        return R.ok(service.reviewPassed(viewId, reason));
    }

    /** 额度时间轴（版本快照聚合，design D7 时间轴抽屉数据源） */
    @GetMapping("/timeline")
    public R<List<Map<String, Object>>> timeline(@RequestParam String groupId) {
        return R.ok(service.timeline(groupId));
    }

    /** 可用额度试算（FR-4.3-2-2 公式 + 应收/未清SO 桩口径明示） */
    @GetMapping("/trial")
    public R<Map<String, Object>> trial(@RequestParam String viewId) {
        return R.ok(service.trial(viewId));
    }
}
