package com.erp.controller;

import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.entity.system.SysParam;
import com.erp.service.SysParamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 通用业务参数接口（design D13：本轮阈值统一入 erp_sys_param）。
 * GET 只读供前端阈值展示；写入默认走各业务服务内部 setValue——
 * POST 调整入口仅 ADMIN（运维/冒烟运行时改参；参数进程内缓存，SQL 直改不生效）。
 */
@Tag(name = "业务参数")
@RestController
@RequestMapping("/api/system/params")
public class SysParamController {

    private final SysParamService paramService;

    public SysParamController(SysParamService paramService) {
        this.paramService = paramService;
    }

    @GetMapping
    @Operation(summary = "全部生效参数", description = "键 → 值 + 说明与调整历史")
    public R<List<SysParam>> list() {
        return R.ok(paramService.listAll());
    }

    @PostMapping("/{key}")
    @Operation(summary = "调整参数（仅 ADMIN；写入即刷进程缓存）")
    public R<Void> set(@PathVariable String key,
                       @RequestParam String value,
                       @RequestParam(defaultValue = "STRING") String valueType,
                       @RequestParam(defaultValue = "MRP") String paramGroup,
                       @RequestParam(defaultValue = "") String remark) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean admin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!admin) {
            throw new ServiceException(403, "调整参数需管理员");
        }
        paramService.setValue(key, value, valueType, paramGroup,
                remark.isEmpty() ? "运行时调整" : remark, "admin");
        return R.ok();
    }
}
