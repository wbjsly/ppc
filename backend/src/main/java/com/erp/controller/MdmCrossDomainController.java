package com.erp.controller;

import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmPriceAgreement;
import com.erp.entity.mdm.MdmPriceAgreementVersion;
import com.erp.entity.ops.MdmOutboxEvent;
import com.erp.service.mdm.MdmCrossDomainService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 跨域共享（客户管理 1.3.3）：事件流台账 + 价格协议（/api/mdm/outbox、/api/mdm/price-agreements） */
@RestController
@RequestMapping("/api/mdm")
public class MdmCrossDomainController {

    private final MdmCrossDomainService service;

    public MdmCrossDomainController(MdmCrossDomainService service) {
        this.service = service;
    }

    // ---------- 事件流 ----------

    @GetMapping("/outbox")
    public R<Map<String, Object>> outboxPage(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String eventType,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(defaultValue = "") String keyword) {
        return R.ok(service.outboxPage(current, size, eventType, status, keyword));
    }

    @GetMapping("/outbox/{id}")
    public R<Map<String, Object>> outboxDetail(@PathVariable String id) {
        return R.ok(service.outboxDetail(id));
    }

    // ---------- 价格协议 ----------

    @GetMapping("/price-agreements")
    public R<Map<String, Object>> agreementPage(@RequestParam(defaultValue = "1") long current,
                                                @RequestParam(defaultValue = "10") long size,
                                                @RequestParam(defaultValue = "") String keyword,
                                                @RequestParam(required = false) String agreementType,
                                                @RequestParam(required = false) String status) {
        return R.ok(service.agreementPage(current, size, keyword, agreementType, status));
    }

    @GetMapping("/price-agreements/{id}")
    public R<MdmPriceAgreement> getAgreement(@PathVariable String id) {
        return R.ok(service.getAgreement(id));
    }

    /** 创建：body = 协议头字段 + lines:[{itemCode,unitPrice,minQty,maxQty}] */
    @PostMapping("/price-agreements")
    public R<MdmPriceAgreement> create(@RequestBody Map<String, Object> body) {
        return R.ok(service.createAgreement(parseHead(body), parseLines(body)));
    }

    @PutMapping("/price-agreements")
    public R<MdmPriceAgreement> update(@RequestBody Map<String, Object> body) {
        return R.ok(service.updateAgreement(parseHead(body), parseLines(body)));
    }

    /** 停用（0/1→3）与恢复（3→按日期推算） */
    @PutMapping("/price-agreements/{id}/stop")
    public R<MdmPriceAgreement> stop(@PathVariable String id,
                                     @RequestParam(required = false) String reason) {
        return R.ok(service.stopAgreement(id, reason));
    }

    @GetMapping("/price-agreements/{id}/versions")
    public R<List<MdmPriceAgreementVersion>> versions(@PathVariable String id) {
        return R.ok(service.agreementVersions(id));
    }

    @GetMapping("/price-agreements/{id}/diff")
    public R<Map<String, Object>> diff(@PathVariable String id,
                                       @RequestParam int from, @RequestParam int to) {
        return R.ok(service.agreementDiff(id, from, to));
    }

    /** 试算（FR-4.3-4-3 优先级子集）：groupId/viewId 至少一个 */
    @GetMapping("/price-agreements/trial")
    public R<Map<String, Object>> trial(@RequestParam(required = false) String groupId,
                                        @RequestParam(required = false) String viewId,
                                        @RequestParam String itemCode,
                                        @RequestParam BigDecimal qty,
                                        @RequestParam(required = false) String date) {
        // LocalDate @RequestParam 依赖全局转换器（本项目转换失败）→ 手动解析 yyyy-MM-dd（025 同款隐患修复）
        LocalDate d = null;
        if (date != null && !date.isBlank()) {
            try {
                d = LocalDate.parse(date);
            } catch (java.time.format.DateTimeParseException e) {
                throw new ServiceException(422, "日期格式须为 yyyy-MM-dd");
            }
        }
        return R.ok(service.trial(groupId, viewId, itemCode, qty, d));
    }

    // ---------- body 解析 ----------

    @SuppressWarnings("unchecked")
    private MdmPriceAgreement parseHead(Map<String, Object> body) {
        MdmPriceAgreement a = new MdmPriceAgreement();
        a.setId((String) body.get("id"));
        a.setPaCode((String) body.get("paCode"));
        a.setPaName((String) body.get("paName"));
        a.setAgreementType((String) body.get("agreementType"));
        a.setCustomerGroupId((String) body.get("customerGroupId"));
        a.setCustomerViewId((String) body.get("customerViewId"));
        a.setEffectiveDate(body.get("effectiveDate") == null ? null
                : LocalDate.parse(String.valueOf(body.get("effectiveDate"))));
        a.setExpireDate(body.get("expireDate") == null || String.valueOf(body.get("expireDate")).isBlank()
                ? null : LocalDate.parse(String.valueOf(body.get("expireDate"))));
        a.setChangeReason((String) body.get("changeReason"));
        return a;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseLines(Map<String, Object> body) {
        Object lines = body.get("lines");
        if (lines == null) {
            return new ArrayList<>(); // 交由服务层「至少一行」校验
        }
        return (List<Map<String, Object>>) lines;
    }
}
