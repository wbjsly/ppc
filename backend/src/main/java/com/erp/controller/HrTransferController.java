package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.hr.HrExit;
import com.erp.entity.hr.HrTransfer;
import com.erp.service.hr.HrExitService;
import com.erp.service.hr.HrTransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * 入转调离（M10-7，employee-transfer）：转正/调岗双人复核/离职交接。
 */
@Tag(name = "入转调离")
@RestController
@RequestMapping("/api/hr/transfers")
@RequiredArgsConstructor
public class HrTransferController {

    private final HrTransferService transferService;
    private final HrExitService exitService;

    // ---------- 转正 ----------

    @PostMapping("/probation")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "发起转正（主管评价）")
    public R<HrTransfer> submitProbation(@RequestBody Map<String, Object> payload) {
        return R.ok(transferService.submitProbation(payload));
    }

    @PostMapping("/probation/{id}/hr-review")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "HR 审核（延期法定上限 L1）并提交审批")
    public R<HrTransfer> hrReviewProbation(@PathVariable String id,
                                           @RequestBody Map<String, String> body) {
        return R.ok(transferService.hrReviewProbation(id, body.get("opinion")));
    }

    // ---------- 调岗 ----------

    @PostMapping("/post")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "发起调岗（满编 L1）")
    public R<HrTransfer> submitPost(@RequestBody Map<String, Object> payload) {
        return R.ok(transferService.submitPostChange(payload));
    }

    @PostMapping("/post/{id}/confirm-out")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "调出部门确认")
    public R<HrTransfer> confirmOut(@PathVariable String id, @RequestBody Map<String, String> body) {
        return R.ok(transferService.confirmDeptOut(id, body.get("opinion")));
    }

    @PostMapping("/post/{id}/confirm-in")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "调入部门确认")
    public R<HrTransfer> confirmIn(@PathVariable String id, @RequestBody Map<String, String> body) {
        return R.ok(transferService.confirmDeptIn(id, body.get("opinion")));
    }

    @PostMapping("/post/{id}/hr-review")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "HR 审核 → 进入双人复核")
    public R<HrTransfer> hrReviewPost(@PathVariable String id, @RequestBody Map<String, String> body) {
        return R.ok(transferService.hrReviewPost(id, body.get("opinion")));
    }

    @PostMapping("/post/{id}/review-sign")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "双人复核签署（C-4.7-03：两人独立五要素比对）")
    public R<HrTransfer> signReview(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(transferService.signReview(id, payload));
    }

    @PostMapping("/post/{id}/adjudicate")
    @PreAuthorize("hasAnyRole('HR_MGR','ADMIN')")
    @Operation(summary = "复核差异裁决（HR 经理）")
    public R<HrTransfer> adjudicate(@PathVariable String id, @RequestBody Map<String, String> body) {
        return R.ok(transferService.adjudicateDiff(id, body.get("decision"), body.get("note")));
    }

    // ---------- 离职 ----------

    @PostMapping("/exits")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "提交离职申请（通知期 L4 提示留痕）")
    public R<HrExit> applyExit(@RequestBody Map<String, Object> payload) {
        return R.ok(exitService.apply(payload));
    }

    @PostMapping("/exits/{id}/start-approval")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "提交离职审批")
    public R<HrExit> startApproval(@PathVariable String id) {
        return R.ok(exitService.startApproval(id));
    }

    @GetMapping("/exits/{id}/checklist")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "交接清单（C-4.7-04：资产/账号列示 + 未完成项）")
    public R<Map<String, Object>> checklist(@PathVariable String id) {
        return R.ok(exitService.handoverChecklist(id));
    }

    @PostMapping("/exits/{id}/assets-return")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "确认资产归还（EAM 卡片持有人清空回写）")
    public R<HrExit> assetsReturn(@PathVariable String id, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> cardIds = (List<String>) body.get("cardIds");
        return R.ok(exitService.confirmAssetReturn(id, cardIds));
    }

    @PostMapping("/exits/{id}/account-handover")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "确认权限移交")
    public R<HrExit> accountHandover(@PathVariable String id) {
        return R.ok(exitService.confirmAccountHandover(id));
    }

    @PostMapping("/exits/{id}/handover-items")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "登记知识文档/在办工作交接项（含接收人）")
    public R<HrExit> handoverItems(@PathVariable String id, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        return R.ok(exitService.registerHandoverItems(id, String.valueOf(body.get("category")), items));
    }

    @PostMapping("/exits/{id}/settle")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "薪酬结算登记 + 社保减员留桩")
    public R<HrExit> settle(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(exitService.settle(id, payload));
    }

    @PostMapping("/exits/{id}/issue-cert")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "生成离场凭证（C-4.7-04 四类交接未齐 L1 硬阻断）")
    public R<HrExit> issueCert(@PathVariable String id) {
        return R.ok(exitService.issueExitCert(id));
    }

    @GetMapping("/exits")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "离职单列表")
    public R<List<HrExit>> listExits(@RequestParam(required = false) String status) {
        return R.ok(exitService.list(status));
    }

    @GetMapping("/exits/{id}")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "离职单详情（含交接清单）")
    public R<Map<String, Object>> exitDetail(@PathVariable String id) {
        return R.ok(exitService.detail(id));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "变动单列表（转正/调岗）")
    public R<List<HrTransfer>> list(@RequestParam(required = false) String type,
                                    @RequestParam(required = false) String status) {
        return R.ok(transferService.list(type, status));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "变动单详情")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(transferService.detail(id));
    }

    @PostMapping("/post/{id}/place-change")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "缴纳地变更设置（HR 审核环节，FR-4.7-4-8；生效时更新主档并开转移单）")
    public R<HrTransfer> placeChange(@PathVariable String id, @RequestBody Map<String, String> body) {
        return R.ok(transferService.updatePlaceChange(id, body.get("placeChange"), body.get("newPlace")));
    }

    @PostMapping("/exits/{id}/retain-note")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "挽留意见（非阻断留痕，FR-4.7-4-12）")
    public R<HrExit> retainNote(@PathVariable String id, @RequestBody Map<String, String> body) {
        return R.ok(exitService.retainNote(id, body.get("note")));
    }

    @PostMapping("/exits/{id}/waive-reduce")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "社保减员手工豁免（放行离场凭证，记录豁免人与原因，FR-4.7-4-15）")
    public R<HrExit> waiveReduce(@PathVariable String id, @RequestBody Map<String, String> body) {
        return R.ok(exitService.waiveReduce(id, body.get("reason")));
    }
}
