package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.sd.DiscountChannel;
import com.erp.entity.sd.PriceAudit;
import com.erp.entity.sd.Promotion;
import com.erp.entity.sd.SpecialPrice;
import com.erp.service.sd.PricingDiscountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 价格折扣与治理接口（3.6.1 价格矩阵 / 3.6.2 折扣矩阵 / 3.6.3 专属折扣 / 3.6.4 取价记录，
 * spec sales-pricing-discount，tasks 5.9）。
 * 权限：SALES / SALES_MGR / SALES_DIRECTOR / ADMIN（配置维护在服务层再分校验 MGR+）。
 */
@Tag(name = "价格折扣")
@RestController
@RequestMapping("/api/sd/pricing")
public class PricingController {

    private final PricingDiscountService service;

    public PricingController(PricingDiscountService service) {
        this.service = service;
    }

    // ---------- 3.6.2 折扣矩阵：渠道折扣配置 ----------

    @GetMapping("/channels")
    public R<Page<DiscountChannel>> channels(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String channel) {
        return R.ok(service.channelPage(current, size, channel));
    }

    @PostMapping("/channels")
    public R<DiscountChannel> saveChannel(@RequestBody DiscountChannel c) {
        return R.ok(service.saveChannel(c));
    }

    @PostMapping("/channels/{id}/stop")
    public R<DiscountChannel> stopChannel(@PathVariable String id, @RequestBody Map<String, String> body) {
        return R.ok(service.stopChannel(id, body.get("reason")));
    }

    @PostMapping("/channels/{id}/enable")
    public R<DiscountChannel> enableChannel(@PathVariable String id) {
        return R.ok(service.enableChannel(id));
    }

    // ---------- 3.6.2 折扣矩阵：促销活动配置 ----------

    @GetMapping("/promotions")
    public R<Page<Promotion>> promotions(@RequestParam(defaultValue = "1") long current,
                                         @RequestParam(defaultValue = "10") long size,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) String status) {
        return R.ok(service.promoPage(current, size, keyword, status));
    }

    @PostMapping("/promotions")
    public R<Promotion> savePromotion(@RequestBody Promotion p) {
        return R.ok(service.savePromotion(p));
    }

    @PostMapping("/promotions/{id}/publish")
    public R<Promotion> publishPromotion(@PathVariable String id) {
        return R.ok(service.publishPromotion(id));
    }

    @PostMapping("/promotions/{id}/stop")
    public R<Promotion> stopPromotion(@PathVariable String id, @RequestBody Map<String, String> body) {
        return R.ok(service.stopPromotion(id, body.get("reason")));
    }

    // ---------- 3.6.2 叠加规则参数 ----------

    @GetMapping("/stack-mode")
    public R<Map<String, String>> stackMode() {
        return R.ok(Map.of("mode", service.getStackMode()));
    }

    @PutMapping("/stack-mode")
    public R<Void> setStackMode(@RequestBody Map<String, String> body) {
        service.setStackMode(body.get("mode"), body.get("operator"));
        return R.ok();
    }

    // ---------- 引擎试算 ----------

    @PostMapping("/calc")
    @Operation(summary = "单行折扣试算",
            description = "三层匹配 + 叠加 + 毛利阻断；带 srcType(SO/QUOTE)+persist=true 时写入审计日志（SO 行计算场景）")
    public R<Map<String, Object>> calc(@RequestBody Map<String, Object> body) {
        String srcType = str(body.get("srcType"));
        boolean persist = Boolean.TRUE.equals(body.get("persist"))
                && ("SO".equals(srcType) || "QUOTE".equals(srcType));
        Object lineNo = body.get("lineNo");
        Integer ln = null;
        if (lineNo != null) {
            try {
                ln = Integer.valueOf(String.valueOf(lineNo));
            } catch (NumberFormatException ignore) {
                ln = null;
            }
        }
        return R.ok(service.calc(
                str(body.get("customerId")),
                str(body.get("itemCode")),
                dec(body.get("qty")),
                dec(body.get("basePrice")),
                persist ? srcType : "CALC",
                str(body.get("srcId")),
                ln,
                persist));
    }

    // ---------- 3.6.1 价格矩阵 ----------

    @GetMapping("/matrix")
    @Operation(summary = "价格矩阵", description = "客户 × SKU 最终价与命中来源（只读）")
    public R<Map<String, Object>> matrix(@RequestParam String customerId,
                                         @RequestParam String itemCodes,
                                         @RequestParam(required = false) BigDecimal qty) {
        List<String> items = Arrays.stream(itemCodes.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return R.ok(service.matrix(customerId, items, qty));
    }

    // ---------- 3.6.4 取价记录 ----------

    @GetMapping("/audit")
    @Operation(summary = "取价记录", description = "基准价/各层折扣/最终价/操作人（只增不改）")
    public R<Page<PriceAudit>> audit(@RequestParam(defaultValue = "1") long current,
                                     @RequestParam(defaultValue = "10") long size,
                                     @RequestParam(required = false) String srcType,
                                     @RequestParam(required = false) String srcId,
                                     @RequestParam(required = false) Integer lineNo,
                                     @RequestParam(required = false) String operatorId,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return R.ok(service.auditPage(current, size, srcType, srcId, lineNo, operatorId, from, to));
    }

    // ---------- 特殊价格审批单（5.6 放行凭据） ----------

    @GetMapping("/special-prices")
    public R<Page<SpecialPrice>> specials(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "10") long size,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String status) {
        return R.ok(service.specialPage(current, size, keyword, status));
    }

    @GetMapping("/special-prices/{id}")
    public R<SpecialPrice> getSpecial(@PathVariable String id) {
        return R.ok(service.getSpecial(id));
    }

    @PostMapping("/special-prices")
    public R<SpecialPrice> applySpecial(@RequestBody SpecialPrice sp) {
        return R.ok(service.applySpecial(sp));
    }

    @PostMapping("/special-prices/{id}/submit")
    public R<SpecialPrice> submitSpecial(@PathVariable String id) {
        return R.ok(service.submitSpecial(id));
    }

    // ---------- helpers ----------

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal dec(Object o) {
        if (o == null) return null;
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
