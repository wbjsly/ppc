package com.erp.controller;

import com.erp.common.R;
import com.erp.service.ChangeWindowService;
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
 * 变更窗口维护（add-permission-approval-flow design D5；16.4.1 窗口维护页将来复用本端点）。
 * 限 ADMIN（SecurityConfig 细规则）。
 */
@RestController
@RequestMapping("/api/system/change-windows")
@RequiredArgsConstructor
public class ChangeWindowController {

    private final ChangeWindowService changeWindowService;

    @Operation(summary = "窗口列表")
    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(changeWindowService.list());
    }

    @Operation(summary = "新建/更新窗口（id 空 = 新建；dayOfWeek 1-7，time HH:mm[:ss]）")
    @PostMapping
    public R<Void> save(@RequestBody Map<String, Object> body) {
        changeWindowService.save(
                body.get("id") == null ? null : String.valueOf(body.get("id")),
                body.get("name") == null ? null : String.valueOf(body.get("name")),
                body.get("dayOfWeek") == null ? null : Integer.parseInt(String.valueOf(body.get("dayOfWeek"))),
                body.get("startTime") == null ? null : String.valueOf(body.get("startTime")),
                body.get("endTime") == null ? null : String.valueOf(body.get("endTime")),
                body.get("status") == null ? "1" : String.valueOf(body.get("status")));
        return R.ok(null);
    }

    @Operation(summary = "删除窗口")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        changeWindowService.delete(id);
        return R.ok(null);
    }
}
