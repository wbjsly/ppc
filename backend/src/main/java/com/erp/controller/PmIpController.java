package com.erp.controller;

import com.erp.common.R;
import com.erp.service.pm.PmIpAgreementService;
import com.erp.service.pm.PmIpArbitrationService;
import com.erp.service.pm.PmIpAssetService;
import com.erp.service.pm.PmAchievementService;
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
 * 知识产权管理（add-ip-management，M11.8）。
 * 11.8.1 成果登记（登记+提取+确认/异议）/ 11.8.2 权属判定（协议维护+判定记录）/
 * 11.8.3 冲突仲裁（双入口+主张+意见+裁决）/ 11.8.4 资产建档（档案台账+申请状态+历史）。
 * 单据级角色校验在服务层（RD_LEAD/LEGAL/ADMIN），控制器不加 @PreAuthorize（铁律⑥）。
 */
@Tag(name = "知识产权管理")
@RestController
@RequestMapping("/api/pm/ip")
@RequiredArgsConstructor
public class PmIpController {

    private final PmIpAgreementService agreementService;
    private final PmAchievementService achievementService;
    private final PmIpArbitrationService arbitrationService;
    private final PmIpAssetService assetService;

    // ==================== 协议（11.8.2） ====================

    @PostMapping("/agreements")
    @Operation(summary = "保存 IP 归属协议（合作比例合计=100% L1；后签覆盖前签）")
    public R<Map<String, Object>> saveAgreement(@RequestBody Map<String, Object> payload) {
        return R.ok(agreementService.save(payload));
    }

    @GetMapping("/agreements")
    @Operation(summary = "协议列表（按项目过滤）")
    public R<Map<String, Object>> listAgreements(@RequestParam(required = false) String projectId) {
        return R.ok(agreementService.list(Map.of("projectId", projectId == null ? "" : projectId)));
    }

    @GetMapping("/agreements/effective")
    @Operation(summary = "项目生效协议（判定引擎/立项卡控共用）")
    public R<Map<String, Object>> effectiveAgreement(@RequestParam String projectId) {
        return R.ok(agreementService.effectiveOf(projectId));
    }

    // ==================== 成果登记（11.8.1） ====================

    @PostMapping("/achievements")
    @Operation(summary = "保存/提交成果（WBS L1；参与人快照；判定引擎）")
    public R<Map<String, Object>> saveAchievement(@RequestBody Map<String, Object> payload) {
        return R.ok(achievementService.save(payload));
    }

    @GetMapping("/achievements/members")
    @Operation(summary = "WBS 任务参与人提取（工时归集聚合）")
    public R<List<Map<String, Object>>> extractMembers(@RequestParam String wbsId) {
        return R.ok(achievementService.extractMembers(wbsId));
    }

    @GetMapping("/achievements")
    @Operation(summary = "成果列表（按项目/状态过滤）")
    public R<Map<String, Object>> listAchievements(@RequestParam(required = false) String projectId,
                                                   @RequestParam(required = false) String status) {
        return R.ok(achievementService.list(Map.of("projectId", projectId == null ? "" : projectId,
                "status", status == null ? "" : status)));
    }

    @GetMapping("/achievements/{id}")
    @Operation(summary = "成果详情（含参与人清单）")
    public R<Map<String, Object>> achievementDetail(@PathVariable String id) {
        return R.ok(achievementService.detail(id));
    }

    @PutMapping("/achievements/{id}/confirm")
    @Operation(summary = "研发负责人确认（→ CONFIRMED + 自动建档）")
    public R<Map<String, Object>> confirmAchievement(@PathVariable String id,
                                                     @RequestBody(required = false) Map<String, Object> payload) {
        return R.ok(achievementService.confirm(id, payload == null ? Map.of() : payload));
    }

    @PutMapping("/achievements/{id}/object")
    @Operation(summary = "有异议（多方主张→自动建仲裁单；单方→法务复核）")
    public R<Map<String, Object>> objectAchievement(@PathVariable String id,
                                                    @RequestBody Map<String, Object> payload) {
        return R.ok(achievementService.object(id, payload));
    }

    @PutMapping("/achievements/{id}/legal-review")
    @Operation(summary = "法务复核结论（回待确认或改判权属）")
    public R<Map<String, Object>> legalReview(@PathVariable String id,
                                              @RequestBody Map<String, Object> payload) {
        return R.ok(achievementService.legalReview(id, payload));
    }

    // ==================== 冲突仲裁（11.8.3） ====================

    @PostMapping("/arbitrations")
    @Operation(summary = "法务独立申请仲裁（兜底入口；在途防重 409）")
    public R<Map<String, Object>> createArbitration(@RequestBody Map<String, Object> payload) {
        return R.ok(arbitrationService.createManual(payload));
    }

    @GetMapping("/arbitrations")
    @Operation(summary = "仲裁单列表（含主张明细）")
    public R<Map<String, Object>> listArbitrations(@RequestParam(required = false) String achieveId,
                                                   @RequestParam(required = false) String status) {
        return R.ok(arbitrationService.list(Map.of("achieveId", achieveId == null ? "" : achieveId,
                "status", status == null ? "" : status)));
    }

    @PostMapping("/arbitrations/{id}/claims")
    @Operation(summary = "提交权属主张（双方各自 record，永久留痕）")
    public R<Map<String, Object>> addClaim(@PathVariable String id,
                                           @RequestBody Map<String, Object> payload) {
        return R.ok(arbitrationService.addClaim(id, payload));
    }

    @PutMapping("/arbitrations/{id}/opinion")
    @Operation(summary = "法务出具仲裁意见（→ OPINION_ISSUED）")
    public R<Map<String, Object>> issueOpinion(@PathVariable String id,
                                               @RequestBody Map<String, Object> payload) {
        return R.ok(arbitrationService.issueOpinion(id, payload));
    }

    @PutMapping("/arbitrations/{id}/confirm")
    @Operation(summary = "双方确认意见（拒绝 → ESCALATED_EXTERNAL）")
    public R<Map<String, Object>> confirmOpinion(@PathVariable String id,
                                                 @RequestBody Map<String, Object> payload) {
        return R.ok(arbitrationService.confirmOpinion(id, payload));
    }

    @PutMapping("/arbitrations/{id}/adjudicate")
    @Operation(summary = "裁决回写（覆盖成果权属 + 回待确认重确认）")
    public R<Map<String, Object>> adjudicate(@PathVariable String id,
                                             @RequestBody Map<String, Object> payload) {
        return R.ok(arbitrationService.adjudicate(id, payload));
    }

    // ==================== 资产建档（11.8.4） ====================

    @GetMapping("/assets")
    @Operation(summary = "档案台账（类型/状态/到期区间筛选）")
    public R<Map<String, Object>> listAssets(@RequestParam(required = false) String type,
                                             @RequestParam(required = false) String applyStatus,
                                             @RequestParam(required = false) String projectId,
                                             @RequestParam(required = false) String expireBefore,
                                             @RequestParam(required = false) String expireAfter) {
        Map<String, Object> q = new java.util.LinkedHashMap<>();
        q.put("type", type == null ? "" : type);
        q.put("applyStatus", applyStatus == null ? "" : applyStatus);
        q.put("projectId", projectId == null ? "" : projectId);
        q.put("expireBefore", expireBefore == null ? "" : expireBefore);
        q.put("expireAfter", expireAfter == null ? "" : expireAfter);
        return R.ok(assetService.list(q));
    }

    @PutMapping("/assets/{id}/apply-status")
    @Operation(summary = "更新申请状态（写状态历史）")
    public R<Map<String, Object>> applyStatus(@PathVariable String id,
                                              @RequestBody Map<String, Object> payload) {
        return R.ok(assetService.applyStatus(id, payload));
    }

    @GetMapping("/assets/{id}/status-log")
    @Operation(summary = "申请状态变更历史")
    public R<Object> statusLog(@PathVariable String id) {
        return R.ok(assetService.statusLog(id));
    }
}
