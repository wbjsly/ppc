package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.entity.intf.IntfCredential;
import com.erp.security.IntfGuard;
import com.erp.service.intf.CredentialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * API 凭证生命周期（spec api-credential-management，菜单 2.8.3「凭证与防护」）。
 * 吊销为高危动作，服务端二次校验 ADMIN（design D5）。
 */
@Tag(name = "接口凭证管理")
@RestController
@RequestMapping("/api/intf/credentials")
public class IntfCredentialController {

    private final CredentialService service;

    public IntfCredentialController(CredentialService service) {
        this.service = service;
    }

    @Operation(summary = "凭证分页（按伙伴/状态筛选）")
    @GetMapping
    public R<Page<IntfCredential>> page(@RequestParam(defaultValue = "1") long current,
                                        @RequestParam(defaultValue = "10") long size,
                                        @RequestParam(required = false) String partnerCode,
                                        @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, partnerCode, status));
    }

    @Operation(summary = "签发凭证（明文仅本次返回，同伙伴 ACTIVE ≤ 2 套）")
    @PostMapping
    public R<Map<String, Object>> issue(@RequestBody Map<String, Object> body) {
        String partnerCode = str(body.get("partnerCode"));
        if (partnerCode == null) {
            throw new ServiceException(400, "partnerCode 不能为空");
        }
        Integer ttl = body.get("ttlDays") == null ? null : Integer.valueOf(String.valueOf(body.get("ttlDays")));
        return R.ok(service.issue(partnerCode, str(body.get("scope")), str(body.get("tier")),
                str(body.get("env")), ttl, str(body.get("remark"))));
    }

    @Operation(summary = "生成一次性分发链接（10 分钟有效）")
    @PostMapping("/{id}/link")
    public R<Map<String, Object>> link(@PathVariable String id) {
        return R.ok(service.createLink(id));
    }

    @Operation(summary = "发起计划内轮换（新凭证灰度，旧凭证到期后 DEPRECATED）")
    @PostMapping("/{id}/rotate")
    public R<Map<String, Object>> rotate(@PathVariable String id) {
        return R.ok(service.rotateStart(id));
    }

    @Operation(summary = "紧急吊销（高危：仅 ADMIN，3 秒内生效）")
    @PostMapping("/{id}/revoke")
    public R<Void> revoke(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        IntfGuard.requireAdmin("凭证紧急吊销");
        service.revoke(id, str(body == null ? null : body.get("reason")));
        return R.ok(null);
    }

    @Operation(summary = "泄露追溯（近 30 天 Trace + 存在/不存在异常调用结论）")
    @GetMapping("/trace")
    public R<Map<String, Object>> trace(@RequestParam String partnerCode) {
        return R.ok(service.trace(partnerCode));
    }

    @Operation(summary = "到期治理调度（30/7/1 日提醒、到期 EXPIRED、轮换观察期推进）")
    @PostMapping("/sweep")
    public R<Integer> sweep() {
        return R.ok(service.sweepExpiry());
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}
