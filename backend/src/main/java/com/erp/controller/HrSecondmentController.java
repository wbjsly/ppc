package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.hr.HrSecondment;
import com.erp.service.hr.HrSecondmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 外派/借调管理（secondment-management，10.8.1 外派 / 10.8.2 借调）。 */
@Tag(name = "外派借调")
@RestController
@RequestMapping("/api/hr/secondments")
@RequiredArgsConstructor
public class HrSecondmentController {

    private final HrSecondmentService secondmentService;
    private final com.erp.service.hr.HrSecondmentVoucherService secondmentVoucherService;

    @GetMapping("/allocations/preview")
    @Operation(summary = "分摊预览（ym 月应付分摊清单）")
    public R<List<Map<String, Object>>> allocPreview(@RequestParam(required = false) String ym) {
        return R.ok(secondmentVoucherService.preview(ym));
    }

    @PostMapping("/allocations/generate")
    @PreAuthorize("hasAnyRole('HR_MGR','ADMIN')")
    @Operation(summary = "月度分摊凭证手动生成（幂等，通常由每月 1 日任务触发）")
    public R<Map<String, Object>> allocGenerate(@RequestBody(required = false) Map<String, Object> body) {
        String ym = body == null ? null : String.valueOf(body.get("ym"));
        return R.ok(secondmentVoucherService.monthlyGenerate(ym));
    }

    @PostMapping
    @Operation(summary = "发起（借调必填协议编号/分摊比例；期限>12月 L4 警示返回 warnings）")
    public R<Map<String, Object>> submit(@RequestBody Map<String, Object> payload) {
        return R.ok(secondmentService.submit(payload));
    }

    @GetMapping
    @Operation(summary = "清单（按类型/状态过滤，10.8.1=DISPATCH / 10.8.2=SECOND）")
    public R<List<HrSecondment>> list(@RequestParam(required = false) String chgType,
                                      @RequestParam(required = false) String status) {
        return R.ok(secondmentService.list(chgType, status));
    }

    @GetMapping("/{id}")
    @Operation(summary = "详情（含协议扫描件列表）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(secondmentService.detail(id));
    }

    @PostMapping("/{id}/confirm-out")
    @Operation(summary = "调出主体确认（agree=false 置 REJECTED）")
    public R<HrSecondment> confirmOut(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(secondmentService.confirmOut(id, String.valueOf(body.get("opinion")),
                !"false".equals(String.valueOf(body.get("agree")))));
    }

    @PostMapping("/{id}/confirm-in")
    @Operation(summary = "调入主体确认（完成即锁定分摊比例）")
    public R<HrSecondment> confirmIn(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(secondmentService.confirmIn(id, String.valueOf(body.get("opinion")),
                !"false".equals(String.valueOf(body.get("agree")))));
    }

    @PostMapping("/{id}/hr-review")
    @Operation(summary = "HR 合规审核（属地政策 L1 / 缴纳地一致性 L4 警示）")
    public R<Map<String, Object>> hrReview(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(secondmentService.hrReview(id, String.valueOf(body.get("opinion"))));
    }

    @PostMapping("/{id}/review-sign")
    @Operation(summary = "双人复核签署（C-4.7-11 五要素，同人不可双签）")
    public R<HrSecondment> signReview(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(secondmentService.signReview(id, payload));
    }

    @PostMapping("/{id}/adjudicate")
    @PreAuthorize("hasAnyRole('HR_MGR','ADMIN')")
    @Operation(summary = "复核差异裁决")
    public R<HrSecondment> adjudicate(@PathVariable String id, @RequestBody Map<String, String> body) {
        return R.ok(secondmentService.adjudicateDiff(id, body.get("decision"), body.get("note")));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('HR_MGR','ADMIN')")
    @Operation(summary = "审批（APPROVED 待生效 / REJECTED）")
    public R<HrSecondment> approve(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(secondmentService.approve(id,
                !"false".equals(String.valueOf(body.get("approve"))),
                String.valueOf(body.get("opinion"))));
    }

    @PostMapping("/{id}/effect")
    @Operation(summary = "生效执行（幂等；外派写回主档+社保双单 / 借调零改动）")
    public R<HrSecondment> effect(@PathVariable String id) {
        return R.ok(secondmentService.effect(id));
    }

    @PostMapping("/{id}/return")
    @Operation(summary = "返岗确认（借调专属，关闭在途）")
    public R<HrSecondment> returnPost(@PathVariable String id, @RequestBody Map<String, String> body) {
        return R.ok(secondmentService.returnPost(id, body.get("note")));
    }

    @PostMapping("/{id}/extend")
    @Operation(summary = "延期申请（累计>24月 L1 拒绝，BR-4.7-05）")
    public R<HrSecondment> extend(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(secondmentService.extend(id,
                Integer.parseInt(String.valueOf(body.get("months"))),
                String.valueOf(body.get("reason"))));
    }

    @PostMapping("/{id}/approve-extend")
    @PreAuthorize("hasAnyRole('HR_MGR','ADMIN')")
    @Operation(summary = "延期审批（L2）")
    public R<HrSecondment> approveExtend(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(secondmentService.approveExtend(id,
                !"false".equals(String.valueOf(body.get("approve"))),
                String.valueOf(body.get("note"))));
    }

    @PostMapping("/remind-scan")
    @Operation(summary = "提醒扫描手动触发（15天返岗提醒 + 过期告警）")
    public R<Map<String, Object>> remindScan() {
        return R.ok(secondmentService.remindScan());
    }

    @GetMapping("/in-flight-flags")
    @Operation(summary = "员工在途标记（员工列表/档案联查「借调中·XX公司」）")
    public R<Map<String, String>> inFlightFlags() {
        return R.ok(secondmentService.inFlightFlags());
    }

    @GetMapping("/allowance-standards")
    @Operation(summary = "外派补贴标准（城市×职级）")
    public R<List<Map<String, Object>>> allowanceStd(@RequestParam(required = false) String place) {
        return R.ok(secondmentService.allowanceStdList(place));
    }
}
