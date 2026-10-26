package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.fin.FinAccount;
import com.erp.entity.fin.FinVoucher;
import com.erp.entity.fin.FinVoucherLine;
import com.erp.entity.fin.FinVoucherTemplate;
import com.erp.service.fin.FinAccountService;
import com.erp.service.fin.FinPeriodService;
import com.erp.service.fin.GlBalanceService;
import com.erp.service.fin.GlVoucherService;
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

import java.util.List;
import java.util.Map;

/**
 * 总账管理（add-gl-management，8.1.1~8.1.5：科目/手工凭证三态/期间结账/余额/模板）。
 */
@Tag(name = "总账管理")
@RestController
@RequestMapping("/api/fin")
@RequiredArgsConstructor
public class GlManagementController {

    private final FinAccountService accountService;
    private final GlVoucherService voucherService;
    private final FinPeriodService periodService;
    private final GlBalanceService balanceService;

    /** 手工凭证请求体（强类型，避免 Map 强转——处置 Controller 教训） */
    public record ManualVoucherPayload(FinVoucher order, List<FinVoucherLine> lines) {
        public List<FinVoucherLine> lines() {
            return lines == null ? List.of() : lines;
        }
    }

    // ================= 8.1.1 科目管理 =================

    @Operation(summary = "科目分页（编码只增停不改——17 域运行依赖）")
    @GetMapping("/accounts")
    public R<Page<FinAccount>> accountPage(@RequestParam(defaultValue = "1") long current,
                                           @RequestParam(defaultValue = "20") long size,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String category) {
        return R.ok(accountService.page(current, size, keyword, status, category));
    }

    @Operation(summary = "新增科目（编码唯一）")
    @PostMapping("/accounts")
    public R<FinAccount> createAccount(@RequestBody FinAccount account) {
        return R.ok(accountService.create(account));
    }

    @Operation(summary = "科目启停（ACTIVE/DISABLED）")
    @PutMapping("/accounts/{code}/status")
    public R<Void> setAccountStatus(@PathVariable String code, @RequestParam String status) {
        accountService.setStatus(code, status);
        return R.ok();
    }

    @Operation(summary = "科目明细（含辅助核算标记）")
    @GetMapping("/accounts/{code}")
    public R<Map<String, Object>> accountDetail(@PathVariable String code) {
        return R.ok(accountService.detail(code));
    }

    // ================= 8.1.2/8.1.3/8.1.4 手工凭证三态 =================

    @Operation(summary = "手工凭证保存（DRAFT；平衡/科目/法人币种全量列示）")
    @PostMapping("/manual-vouchers")
    public R<FinVoucher> createManual(@RequestBody ManualVoucherPayload payload) {
        FinVoucher header = payload.order() == null ? new FinVoucher() : payload.order();
        return R.ok(voucherService.createManual(header, payload.lines()));
    }

    @Operation(summary = "手工凭证分页（MANUAL only，状态筛选）")
    @GetMapping("/manual-vouchers")
    public R<Page<FinVoucher>> manualPage(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "20") long size,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String keyword) {
        return R.ok(voucherService.manualPage(current, size, status, keyword));
    }

    @Operation(summary = "凭证详情（含行，供审核/打印）")
    @GetMapping("/manual-vouchers/{id}")
    public R<Map<String, Object>> voucherDetail(@PathVariable String id) {
        return R.ok(voucherService.detail(id));
    }

    @Operation(summary = "审核通过（DRAFT→AUDITED；C-4.6-07 制单≠审核人）")
    @PostMapping("/manual-vouchers/{id}/audit")
    public R<FinVoucher> audit(@PathVariable String id) {
        return R.ok(voucherService.audit(id));
    }

    @Operation(summary = "审核驳回（留因）")
    @PostMapping("/manual-vouchers/{id}/reject")
    public R<Void> reject(@PathVariable String id, @RequestParam(required = false) String reason) {
        voucherService.reject(id, reason);
        return R.ok();
    }

    @Operation(summary = "过账（AUDITED→POSTED + 写余额；期间 CLOSED 阻断）")
    @PostMapping("/manual-vouchers/{id}/post")
    public R<FinVoucher> post(@PathVariable String id) {
        return R.ok(voucherService.post(id));
    }

    @Operation(summary = "作废 DRAFT")
    @PostMapping("/manual-vouchers/{id}/void")
    public R<Void> voidDraft(@PathVariable String id) {
        voucherService.voidDraft(id);
        return R.ok();
    }

    @Operation(summary = "红字冲销（BR-4.6-09；反向冲余额）")
    @PostMapping("/manual-vouchers/{id}/reverse")
    public R<FinVoucher> reverse(@PathVariable String id,
                                 @RequestParam(required = false) String reason) {
        return R.ok(voucherService.reverse(id, reason, null));
    }

    // ================= 凭证模板 =================

    @Operation(summary = "模板分页")
    @GetMapping("/voucher-templates")
    public R<Page<FinVoucherTemplate>> templatePage(@RequestParam(defaultValue = "1") long current,
                                                    @RequestParam(defaultValue = "20") long size,
                                                    @RequestParam(required = false) String keyword) {
        return R.ok(voucherService.templatePage(current, size, keyword));
    }

    @Operation(summary = "模板保存（行 JSON 快照）")
    @PostMapping("/voucher-templates")
    public R<FinVoucherTemplate> saveTemplate(@RequestBody FinVoucherTemplate template) {
        return R.ok(voucherService.saveTemplate(template));
    }

    @Operation(summary = "模板删除")
    @PostMapping("/voucher-templates/{id}/delete")
    public R<Void> deleteTemplate(@PathVariable String id) {
        voucherService.deleteTemplate(id);
        return R.ok();
    }

    @Operation(summary = "模板一键调用（行草稿，停用科目标注不阻断）")
    @GetMapping("/voucher-templates/{id}/apply")
    public R<Map<String, Object>> applyTemplate(@PathVariable String id) {
        return R.ok(voucherService.applyTemplate(id));
    }

    // ================= 8.1.4 余额查询 =================

    @Operation(summary = "科目余额四栏（期初/本期借/本期贷/期末，按科目/辅助过滤）")
    @GetMapping("/balances")
    public R<List<Map<String, Object>>> balances(@RequestParam String period,
                                                 @RequestParam(required = false) String accountCode,
                                                 @RequestParam(required = false) String auxKey) {
        return R.ok(balanceService.query(period, accountCode, auxKey));
    }

    // ================= 8.1.5 期末结账 =================

    @Operation(summary = "当前期间（惰性建档 OPEN）")
    @GetMapping("/periods/current")
    public R<Map<String, Object>> currentPeriod() {
        return R.ok(periodService.current());
    }

    @Operation(summary = "期间列表")
    @GetMapping("/periods")
    public R<List<Map<String, Object>>> periods() {
        return R.ok(periodService.list());
    }

    @Operation(summary = "结账检查清单（未过账手工凭证/试算平衡/资产勾稽/汇率覆盖）")
    @GetMapping("/periods/{period}/checklist")
    public R<Map<String, Object>> checklist(@PathVariable String period) {
        return R.ok(periodService.checklist(period));
    }

    @Operation(summary = "期末结账（清单全绿→CLOSED；任一失败逐项列示）")
    @PostMapping("/periods/{period}/close")
    public R<Map<String, Object>> close(@PathVariable String period) {
        return R.ok(periodService.close(period));
    }
}
