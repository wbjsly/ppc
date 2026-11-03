package com.erp.controller;

import com.erp.common.R;
import com.erp.service.system.DashLayoutService;
import com.erp.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工作台三层布局（change add-configurable-workbench，spec personal-workbench，design D3）：
 * /mine 与 /user 登录即可（个人自助）；/layers、/role、/global 限 ROLE_ADMIN（模板分发）。
 */
@Tag(name = "工作台布局")
@RestController
@RequestMapping("/api/dash/layout")
@RequiredArgsConstructor
public class DashLayoutController {

    private final DashLayoutService dashLayoutService;

    @Operation(summary = "合并后的个人工作台布局（GLOBAL→ROLE→USER 差量合并）")
    @GetMapping("/mine")
    public R<Map<String, Object>> mine() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        List<String> roles = new ArrayList<>();
        if (auth != null && auth.getAuthorities() != null) {
            auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        }
        return R.ok(dashLayoutService.merged(SecurityUtils.getCurrentUserId(), roles));
    }

    @Operation(summary = "三层原始值回显（全局 + 全部角色模板）")
    @GetMapping("/layers")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Map<String, Object>> layers() {
        return R.ok(dashLayoutService.layers());
    }

    @Operation(summary = "读单层原始布局（抽屉按角色回显）")
    @GetMapping("/layer")
    @PreAuthorize("hasRole('ADMIN')")
    public R<String> layer(@RequestParam String scope, @RequestParam String refId) {
        return R.ok(dashLayoutService.layer(scope, refId));
    }

    @Operation(summary = "保存个人布局（body 即布局 JSON；空 body = 恢复默认清层）")
    @PutMapping("/user")
    public R<Void> saveUser(@RequestBody(required = false) String layoutJson) {
        dashLayoutService.saveUser(SecurityUtils.getCurrentUserId(), layoutJson);
        return R.ok(null);
    }

    @Operation(summary = "保存角色模板（ADMIN 分发）")
    @PutMapping("/role/{role}")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> saveRole(@PathVariable String role,
                            @RequestBody(required = false) String layoutJson) {
        dashLayoutService.saveRole(role, layoutJson);
        return R.ok(null);
    }

    @Operation(summary = "保存全局默认（ADMIN 分发）")
    @PutMapping("/global")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> saveGlobal(@RequestBody(required = false) String layoutJson) {
        dashLayoutService.saveGlobal(layoutJson);
        return R.ok(null);
    }
}
