package com.erp.controller;

import com.erp.common.R;
import com.erp.service.PermMatrixService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 权限矩阵端点（add-role-permission-console spec：权限矩阵与 override 覆盖层）。
 * 限 ADMIN（SecurityConfig 细规则，置于域兜底之前）。
 */
@RestController
@RequestMapping("/api/system/perm-matrix")
@RequiredArgsConstructor
public class PermMatrixController {

    private final PermMatrixService matrixService;

    @Operation(summary = "角色 × 菜单合并矩阵（基线 ∪ override，含每格来源标注）")
    @GetMapping
    public R<List<Map<String, Object>>> matrix(@RequestParam String role) {
        return R.ok(matrixService.matrixForRole(role));
    }

    @Operation(summary = "勾选/取消某角色对某菜单的授权（仅落 override 差异格，不改基线）")
    @PostMapping("/cells")
    public R<Void> setCell(@RequestBody Map<String, Object> body) {
        String roleCode = String.valueOf(body.get("roleCode"));
        String menuId = String.valueOf(body.get("menuId"));
        Object checked = body.get("checked");
        boolean on = Boolean.parseBoolean(String.valueOf(checked));
        matrixService.setCell(roleCode, menuId, on);
        return R.ok(null);
    }

    @Operation(summary = "清除某角色全部矩阵覆盖（物理删除，回退 099 基线口径）")
    @DeleteMapping("/overrides/{roleCode}")
    public R<Void> clearOverrides(@PathVariable String roleCode) {
        matrixService.clearRole(roleCode);
        return R.ok(null);
    }
}
