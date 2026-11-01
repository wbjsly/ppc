package com.erp.controller;

import com.erp.common.R;
import com.erp.service.intf.GrayReleaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 凭证/版本灰度切流量（add-intf-portal-menus，spec interface-canary-release，菜单 15.5.4）。
 * 配置/切量/回滚高危动作限 ADMIN（服务层二次校验）；观察窗口错误率超阈自动回滚。
 */
@Tag(name = "灰度切流量")
@RestController
@RequestMapping("/api/intf/gray-releases")
public class GrayReleaseController {

    private final GrayReleaseService service;

    public GrayReleaseController(GrayReleaseService service) {
        this.service = service;
    }

    @Operation(summary = "配置灰度规则（ADMIN）")
    @PostMapping
    public R<Map<String, Object>> createRelease(@RequestBody Map<String, Object> payload) {
        return R.ok(service.createRelease(payload));
    }

    @Operation(summary = "灰度规则列表")
    @GetMapping
    public R<List<Map<String, Object>>> listReleases() {
        return R.ok(service.listReleases());
    }

    @Operation(summary = "灰度规则详情（含切量记录）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> getRelease(@PathVariable String id) {
        return R.ok(service.getRelease(id));
    }

    @Operation(summary = "执行切量至下一档（ADMIN）")
    @PostMapping("/{id}/step")
    public R<Map<String, Object>> stepUp(@PathVariable String id) {
        return R.ok(service.stepUp(id));
    }

    @Operation(summary = "手动回滚到上一档（首档中止；ADMIN）")
    @PostMapping("/{id}/rollback")
    public R<Map<String, Object>> rollback(@PathVariable String id) {
        return R.ok(service.rollback(id));
    }
}
