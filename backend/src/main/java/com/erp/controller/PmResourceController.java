package com.erp.controller;

import com.erp.common.R;
import com.erp.service.pm.PmResourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 资源负载与产能（project-initiation，FR-4.8-2-5；11.3.2 页内 Tab）。 */
@Tag(name = "项目资源")
@RestController
@RequestMapping("/api/pm/resources")
@RequiredArgsConstructor
public class PmResourceController {

    private final PmResourceService resourceService;

    @GetMapping("/load")
    @Operation(summary = "人员负载视图（人×日；超产能/跨项目重叠 warning，上限 366 天）")
    public R<List<Map<String, Object>>> loadView(@RequestParam String from,
                                                 @RequestParam String to) {
        return R.ok(resourceService.loadView(from, to));
    }

    @GetMapping("/capacity")
    @Operation(summary = "产能日历行清单")
    public R<List<Map<String, Object>>> capacityList(@RequestParam(required = false) String userId,
                                                     @RequestParam(required = false) String from,
                                                     @RequestParam(required = false) String to) {
        return R.ok(resourceService.capacityList(userId, from, to));
    }

    @PostMapping("/capacity")
    @PreAuthorize("hasAnyRole('PMO','ADMIN')")
    @Operation(summary = "保存产能行（PMO/ADMIN；0~24 小时）")
    public R<Map<String, Object>> saveCapacity(@RequestBody Map<String, Object> payload) {
        return R.ok(resourceService.saveCapacity(payload));
    }
}
