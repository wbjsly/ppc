package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.sd.CreditCheck;
import com.erp.entity.sd.CreditFreeze;
import com.erp.entity.sd.PrepaymentNotice;
import com.erp.service.sd.CreditControlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 客户信用接口（3.3.1 信用检查 / 3.3.2 信用冻结看板 / 3.3.3 预收处理，
 * spec customer-credit-control，tasks 6.9）。
 * 权限：CREDIT_ADMIN / FINANCE_MGR / ADMIN；预收到账确认仅 FINANCE_MGR / ADMIN（服务层再校验）。
 */
@Tag(name = "客户信用")
@RestController
@RequestMapping("/api/sd/credit")
public class CreditController {

    private final CreditControlService service;

    public CreditController(CreditControlService service) {
        this.service = service;
    }

    // ---------- 3.3.1 信用检查 ----------

    @PostMapping("/check")
    @Operation(summary = "实时信用检查", description = "四因子 + 账龄 + 及时率；persist 落检查记录")
    public R<Map<String, Object>> check(@RequestBody Map<String, Object> body) {
        Object amount = body.get("orderAmount");
        BigDecimal orderAmount = null;
        if (amount != null) {
            try {
                orderAmount = new BigDecimal(String.valueOf(amount));
            } catch (NumberFormatException ignore) {
                orderAmount = null;
            }
        }
        boolean persist = !Boolean.FALSE.equals(body.get("persist"));
        return R.ok(service.check(str(body.get("customerId")), orderAmount,
                str(body.get("soId")), str(body.get("soNo")), persist));
    }

    @GetMapping("/checks")
    @Operation(summary = "检查记录", description = "3.3.1 判定结果与四因子快照")
    public R<Page<CreditCheck>> checks(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "10") long size,
                                       @RequestParam(required = false) String customerId,
                                       @RequestParam(required = false) String result) {
        return R.ok(service.checkPage(current, size, customerId, result));
    }

    /** 建单即检/变更重跑：对指定 SO 执行并按结论标记（D5 / BR-4.3-30） */
    @PostMapping("/recheck-so/{soId}")
    @Operation(summary = "SO 信用重检", description = "未过 → 置 CREDIT_FREEZE 挂起并生成预收通知")
    public R<Map<String, Object>> recheckSo(@PathVariable String soId) {
        return R.ok(service.recheckSo(soId));
    }

    // ---------- 3.3.2 信用冻结看板 ----------

    @GetMapping("/freezes")
    @Operation(summary = "冻结看板", description = "缺口、占用、冻结时长与解冻入口")
    public R<Page<CreditFreeze>> freezes(@RequestParam(defaultValue = "1") long current,
                                         @RequestParam(defaultValue = "10") long size,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String keyword) {
        return R.ok(service.freezePage(current, size, status, keyword));
    }

    @GetMapping("/freezes/{id}")
    public R<CreditFreeze> getFreeze(@PathVariable String id) {
        return R.ok(service.getFreeze(id));
    }

    /** 人工解冻（账龄审批通过等）：回前一稳定状态 + 解冻确认单 */
    @PostMapping("/freezes/{id}/unfreeze")
    public R<CreditFreeze> unfreeze(@PathVariable String id, @RequestBody Map<String, String> body) {
        return R.ok(service.unfreeze(id, body.getOrDefault("method", "MANUAL"), body.get("remark")));
    }

    /** 信用特批（BR-4.3-18：上限校验 + 理由必填 + 永久留痕） */
    @PostMapping("/freezes/{id}/special-approve")
    public R<CreditFreeze> specialApprove(@PathVariable String id, @RequestBody Map<String, Object> body) {
        BigDecimal amount = null;
        if (body.get("amount") != null) {
            try {
                amount = new BigDecimal(String.valueOf(body.get("amount")));
            } catch (NumberFormatException ignore) {
                amount = null;
            }
        }
        return R.ok(service.specialApprove(id, amount, str(body.get("reason"))));
    }

    // ---------- 3.3.3 预收处理 ----------

    @GetMapping("/notices")
    public R<Page<PrepaymentNotice>> notices(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String keyword) {
        return R.ok(service.noticePage(current, size, status, keyword));
    }

    @PostMapping("/notices/{id}/notify")
    @Operation(summary = "通知销售与客户", description = "留痕通知时间")
    public R<PrepaymentNotice> notify(@PathVariable String id) {
        return R.ok(service.notify(id));
    }

    @PostMapping("/notices/{id}/register")
    @Operation(summary = "到账登记", description = "销售/客服登记，累计金额与尚差额")
    public R<PrepaymentNotice> register(@PathVariable String id, @RequestBody Map<String, Object> body) {
        BigDecimal amount = null;
        if (body.get("amount") != null) {
            try {
                amount = new BigDecimal(String.valueOf(body.get("amount")));
            } catch (NumberFormatException ignore) {
                amount = null;
            }
        }
        return R.ok(service.registerReceived(id, amount, str(body.get("remark"))));
    }

    @PostMapping("/notices/{id}/confirm")
    @Operation(summary = "财务确认到账", description = "仅 FINANCE_MGR/ADMIN；足额自动解冻 + 解冻确认单，不足保持冻结")
    public R<Map<String, Object>> confirm(@PathVariable String id) {
        return R.ok(service.confirmReceived(id));
    }

    // ---------- helpers ----------

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
