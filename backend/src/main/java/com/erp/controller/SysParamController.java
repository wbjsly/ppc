package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.system.SysParam;
import com.erp.service.SysParamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 通用业务参数只读接口（design D13：本轮阈值统一入 erp_sys_param）。
 * 前端阈值展示（毛利阈值/审批档位/有效期天数）从这里取，避免硬编码；
 * 写入走各业务服务内部 setValue（暂不开放在线编辑页）。
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
}
