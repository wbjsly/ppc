package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.mdm.MdmExchangeRate;
import com.erp.entity.mdm.MdmExchangeRateVersion;
import com.erp.service.mdm.MdmExchangeRateService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 汇率维护（汇率管理 1.5.1，/api/mdm/exchange-rates） */
@RestController
@RequestMapping("/api/mdm/exchange-rates")
public class MdmExchangeRateController {

    private final MdmExchangeRateService service;

    public MdmExchangeRateController(MdmExchangeRateService service) {
        this.service = service;
    }

    /** 分页（含 lifecycle 计算态筛选：NOT_EFFECTIVE/EFFECTIVE/EXPIRED） */
    @GetMapping
    public R<java.util.Map<String, Object>> page(@RequestParam(defaultValue = "1") long current,
                                                 @RequestParam(defaultValue = "10") long size,
                                                 @RequestParam(required = false) String baseCcy,
                                                 @RequestParam(required = false) String quoteCcy,
                                                 @RequestParam(required = false) String rateType,
                                                 @RequestParam(required = false) String lifecycle) {
        var page = service.page(current, size, baseCcy, quoteCcy, rateType, lifecycle);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("records", page.getRecords());
        result.put("total", page.getTotal());
        return R.ok(result);
    }

    @GetMapping("/{id}")
    public R<MdmExchangeRate> getOne(@PathVariable String id) {
        return R.ok(service.getById(id));
    }

    /** 创建（BR-4.1-16 必填 + BR-4.1-17 区间校验） */
    @PostMapping
    public R<MdmExchangeRate> create(@RequestBody MdmExchangeRate rate) {
        return R.ok(service.create(rate));
    }

    /** 变更（序列键锁定 + 历史 append-only + 重跑区间校验） */
    @PutMapping
    public R<MdmExchangeRate> update(@RequestBody MdmExchangeRate rate) {
        return R.ok(service.update(rate));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        service.delete(id);
        return R.ok();
    }

    /** 试算（FR-4.6-1-5 预铺：缺省类型回退 / 缺失明示 reasons） */
    @GetMapping("/trial")
    public R<Map<String, Object>> trial(@RequestParam String baseCcy,
                                        @RequestParam String quoteCcy,
                                        @RequestParam(required = false) String date,
                                        @RequestParam(required = false) String rateType) {
        // LocalDate @RequestParam 依赖全局转换器（本项目转换失败）→ 手动解析 yyyy-MM-dd
        LocalDate d = null;
        if (date != null && !date.isBlank()) {
            try {
                d = LocalDate.parse(date);
            } catch (java.time.format.DateTimeParseException e) {
                throw new com.erp.common.ServiceException(422, "日期格式须为 yyyy-MM-dd");
            }
        }
        return R.ok(service.trial(baseCcy, quoteCcy, d, rateType));
    }

    @GetMapping("/{id}/versions")
    public R<List<MdmExchangeRateVersion>> versions(@PathVariable String id) {
        return R.ok(service.versions(id));
    }

    @GetMapping("/{id}/diff")
    public R<Map<String, Object>> diff(@PathVariable String id,
                                       @RequestParam int from, @RequestParam int to) {
        return R.ok(service.diff(id, from, to));
    }

    /** 区间历史链（1.5.3）：币对×类型全量升序，含已失效与计算态；三参缺省 422 */
    @GetMapping("/sequence")
    public R<List<Map<String, Object>>> sequence(@RequestParam(required = false) String baseCcy,
                                                 @RequestParam(required = false) String quoteCcy,
                                                 @RequestParam(required = false) String rateType) {
        return R.ok(service.sequence(baseCcy, quoteCcy, rateType));
    }

    /** 全局版本时间线（1.5.3）：跨记录快照倒序分页 + 事件列 */
    @GetMapping("/history")
    public R<Map<String, Object>> history(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "10") long size,
                                          @RequestParam(required = false) String rateType,
                                          @RequestParam(required = false) String opType,
                                          @RequestParam(required = false) String keyword) {
        return R.ok(service.history(current, size, rateType, opType, keyword));
    }
}
