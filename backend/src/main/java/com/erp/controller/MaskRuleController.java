package com.erp.controller;

import com.erp.common.R;
import com.erp.service.MaskRuleService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 脱敏规则维护（16.7.3，限 ADMIN）；规则改动后 MaskService 缓存即时失效。
 */
@RestController
@RequestMapping("/api/system/mask-rules")
@RequiredArgsConstructor
public class MaskRuleController {

    private final MaskRuleService maskRuleService;

    @Operation(summary = "规则列表")
    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(maskRuleService.list());
    }

    @Operation(summary = "新建/更新规则（id 空 = 新建；pattern 为 Java 正则，maskTemplate 支持 $1/$2）")
    @PostMapping
    public R<Void> save(@RequestBody Map<String, Object> body) {
        maskRuleService.save(
                body.get("id") == null ? null : String.valueOf(body.get("id")),
                body.get("code") == null ? null : String.valueOf(body.get("code")),
                body.get("name") == null ? null : String.valueOf(body.get("name")),
                body.get("pattern") == null ? null : String.valueOf(body.get("pattern")),
                body.get("maskTemplate") == null ? null : String.valueOf(body.get("maskTemplate")),
                body.get("status") == null ? "1" : String.valueOf(body.get("status")),
                body.get("remark") == null ? null : String.valueOf(body.get("remark")));
        return R.ok(null);
    }

    @Operation(summary = "删除规则（逻辑删）")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        maskRuleService.delete(id);
        return R.ok(null);
    }
}
