package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.dao.intf.IntfSandboxCaseDao;
import com.erp.entity.intf.IntfContract;
import com.erp.entity.intf.IntfRelease;
import com.erp.entity.intf.IntfSandboxCase;
import com.erp.security.IntfGuard;
import com.erp.service.intf.ContractService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 接入治理（spec interface-onboarding，菜单 2.8.3「接入治理」Tab）。 */
@Tag(name = "接口接入治理")
@RestController
@RequestMapping("/api/intf/onboarding")
public class IntfOnboardController {

    private final ContractService service;
    private final IntfSandboxCaseDao caseDao;

    public IntfOnboardController(ContractService service, IntfSandboxCaseDao caseDao) {
        this.service = service;
        this.caseDao = caseDao;
    }

    @Operation(summary = "契约分页")
    @GetMapping("/contracts")
    public R<Page<IntfContract>> contracts(@RequestParam(defaultValue = "1") long current,
                                           @RequestParam(defaultValue = "10") long size,
                                           @RequestParam(required = false) String partnerCode,
                                           @RequestParam(required = false) String status) {
        return R.ok(service.contractPage(current, size, partnerCode, status));
    }

    @Operation(summary = "接口需求登记 → 契约创建（v1.0.0 起，脱敏规则必填）")
    @PostMapping("/contracts")
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return R.ok(service.create(body));
    }

    @Operation(summary = "提交契约评审（3 个工作日时限）")
    @PostMapping("/contracts/{id}/submit-review")
    public R<Map<String, Object>> submitReview(@PathVariable String id) {
        return R.ok(service.submitReview(id));
    }

    @Operation(summary = "评审动作（PASS 发布 / REJECT 累计 3 次自动关闭）")
    @PostMapping("/contracts/{id}/review")
    public R<Map<String, Object>> review(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Object result = body.get("result");
        Object opinion = body.get("opinion");
        return R.ok(service.review(id, result == null ? "PASS" : String.valueOf(result),
                opinion == null ? null : String.valueOf(opinion)));
    }

    @Operation(summary = "版本变更（不兼容必须升 Major + 旧版本保留 180 天）")
    @PostMapping("/contracts/{id}/version")
    public R<Map<String, Object>> version(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(service.changeVersion(id, body));
    }

    @Operation(summary = "沙箱联调通过率（<100% 不可申请放行）")
    @GetMapping("/contracts/{id}/pass-rate")
    public R<Map<String, Object>> passRate(@PathVariable String id) {
        return R.ok(service.passRate(id));
    }

    @Operation(summary = "登记沙箱联调用例执行结果")
    @PostMapping("/sandbox-cases")
    public R<Map<String, Object>> addCase(@RequestBody Map<String, Object> body) {
        String contractId = str(body.get("contractId"));
        if (contractId == null) {
            throw new ServiceException(400, "contractId 必填");
        }
        IntfSandboxCase c = new IntfSandboxCase();
        c.setContractId(contractId);
        c.setPartnerCode(str(body.get("partnerCode")));
        c.setCaseNo(str(body.get("caseNo")) == null ? "C" + System.currentTimeMillis() : str(body.get("caseNo")));
        c.setCaseType(str(body.get("caseType")) == null ? "POSITIVE" : str(body.get("caseType")));
        c.setFieldPath(str(body.get("fieldPath")));
        c.setExpect(str(body.get("expect")));
        c.setActual(str(body.get("actual")));
        c.setResult("FAIL".equalsIgnoreCase(str(body.get("result"))) ? "FAIL" : "PASS");
        c.setErrorLoc(str(body.get("errorLoc")));
        c.setRunAt(LocalDateTime.now());
        caseDao.insert(c);
        return R.ok(Map.<String, Object>of("id", c.getId(), "caseNo", c.getCaseNo(), "result", c.getResult()));
    }

    @Operation(summary = "沙箱用例列表")
    @GetMapping("/sandbox-cases")
    public R<List<IntfSandboxCase>> cases(@RequestParam String contractId) {
        return R.ok(caseDao.selectList(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<IntfSandboxCase>().eq(IntfSandboxCase::getContractId, contractId)));
    }

    @Operation(summary = "申请生产放行（通过率须 100%）")
    @PostMapping("/releases")
    public R<Map<String, Object>> requestRelease(@RequestBody Map<String, Object> body) {
        return R.ok(service.requestRelease(body));
    }

    @Operation(summary = "放行单分页")
    @GetMapping("/releases")
    public R<Page<IntfRelease>> releases(@RequestParam(defaultValue = "1") long current,
                                         @RequestParam(defaultValue = "10") long size,
                                         @RequestParam(required = false) String status) {
        return R.ok(service.releasePage(current, size, status));
    }

    @Operation(summary = "生产放行双人会签（高危：仅 ADMIN，发起人≠复核人）")
    @PostMapping("/releases/{id}/review")
    public R<Map<String, Object>> reviewRelease(@PathVariable String id) {
        IntfGuard.requireAdmin("生产放行会签");
        return R.ok(service.reviewRelease(id));
    }

    @Operation(summary = "观察期满转正式运行")
    @PostMapping("/releases/{id}/observe-complete")
    public R<Map<String, Object>> observeComplete(@PathVariable String id) {
        return R.ok(service.completeObservation(id));
    }

    @Operation(summary = "观察期归档（内容只读）")
    @PostMapping("/releases/{id}/archive")
    public R<Map<String, Object>> archive(@PathVariable String id) {
        return R.ok(service.archive(id));
    }

    @Operation(summary = "观察期日报（调用量/错误率/P95/限流次数）")
    @GetMapping("/releases/{id}/daily")
    public R<Map<String, Object>> daily(@PathVariable String id) {
        return R.ok(service.observationDaily(id));
    }

    @Operation(summary = "接入治理调度（评审催办 + 废弃倒计时预告）")
    @PostMapping("/sweep")
    public R<Integer> sweep() {
        return R.ok(service.sweep());
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}
