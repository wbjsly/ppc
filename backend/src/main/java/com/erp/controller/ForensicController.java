package com.erp.controller;

import com.erp.common.R;
import com.erp.service.ForensicService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 审计取证导出（16.7.2，S-5.6-07）：申请/双签/生成包/分片下载/销毁。
 * 双签端点 URL 级 authenticated（签署人持 UAR_AUDITOR/DPO 角色，不必是 ADMIN，沿 16.3 D3 先例），
 * 角色校验在 Service 内按 DB 持有判定。
 */
@RestController
@RequestMapping("/api/system/forensic")
@RequiredArgsConstructor
public class ForensicController {

    private final ForensicService forensicService;

    @Operation(summary = "调阅申请（目的 ≥10 字 + 时间范围 + 操作类型/对象范围过滤）")
    @PostMapping("/requests")
    public R<Map<String, Object>> apply(@RequestBody Map<String, Object> body) {
        return R.ok(forensicService.apply(
                body.get("purpose") == null ? null : String.valueOf(body.get("purpose")),
                body.get("rangeFrom") == null ? null : String.valueOf(body.get("rangeFrom")),
                body.get("rangeTo") == null ? null : String.valueOf(body.get("rangeTo")),
                body.get("actionFilter") == null ? null : String.valueOf(body.get("actionFilter")),
                body.get("objectFilter") == null ? null : String.valueOf(body.get("objectFilter"))));
    }

    @Operation(summary = "申请列表（含包状态）")
    @GetMapping("/requests")
    public R<List<Map<String, Object>>> list() {
        return R.ok(forensicService.list());
    }

    @Operation(summary = "内审签署（ROLE_UAR_AUDITOR；自签 403）")
    @PostMapping("/requests/{id}/sign-audit")
    public R<Void> signAudit(@PathVariable String id, @RequestBody Map<String, Object> body) {
        forensicService.signAudit(id, body.get("opinion") == null ? null : String.valueOf(body.get("opinion")));
        return R.ok(null);
    }

    @Operation(summary = "DPO 签署（ROLE_DPO；内审未签 422）")
    @PostMapping("/requests/{id}/sign-dpo")
    public R<Void> signDpo(@PathVariable String id, @RequestBody Map<String, Object> body) {
        forensicService.signDpo(id, body.get("opinion") == null ? null : String.valueOf(body.get("opinion")));
        return R.ok(null);
    }

    @Operation(summary = "生成取证包（双签齐备 + 封条预计算）")
    @PostMapping("/requests/{id}/packages")
    public R<Map<String, Object>> generate(@PathVariable String id) {
        return R.ok(forensicService.generatePackage(id));
    }

    @Operation(summary = "分片下载（重算 SHA-256 比对封条；CSV UTF-8）")
    @GetMapping("/packages/{packageId}/shards/{shard}")
    public ResponseEntity<byte[]> download(@PathVariable String packageId, @PathVariable int shard) {
        Map<String, Object> out = forensicService.download(packageId, shard);
        byte[] csv = (byte[]) out.get("content");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + out.get("filename") + "\"")
                .header("X-Sha256", String.valueOf(out.get("sha256")))
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(csv);
    }

    @Operation(summary = "使用完毕确认（提前销毁，幂等）")
    @PostMapping("/packages/{packageId}/confirm-used")
    public R<Void> confirmUsed(@PathVariable String packageId) {
        forensicService.confirmUsed(packageId);
        return R.ok(null);
    }

    @Operation(summary = "过期销毁扫描（7 天届满 → DESTROYED，幂等）")
    @PostMapping("/sweep-expired")
    public R<Map<String, Object>> sweepExpired() {
        return R.ok(forensicService.sweepExpired());
    }
}
