package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.mrp.WcShift;
import com.erp.entity.system.ShiftTemplate;
import com.erp.entity.system.WorkCalendar;
import com.erp.service.system.WorkCalendarService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 工作日历/班次维护接口（change add-dispatch-management，spec capacity-load，
 * 菜单 16.11 两叶子）。写操作仅 ADMIN（service 层校验，PLANNER 写 403）；读取登录即可。
 */
@Tag(name = "工作日历与班次")
@RestController
@RequestMapping("/api/system/calendar")
public class WorkCalendarController {

    private final WorkCalendarService service;

    public WorkCalendarController(WorkCalendarService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "日期区间日历", description = "from/to 均含；未维护日期不在结果中（Fallback 判定在排程侧）")
    public R<List<WorkCalendar>> listDates(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return R.ok(service.listDates(from, to));
    }

    @PutMapping("/{date}")
    @Operation(summary = "维护单日（upsert）", description = "isWorkday 0/1；修改留痕操作人与 verNo")
    public R<WorkCalendar> upsertDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestBody Map<String, Object> body) {
        String isWorkday = body.get("isWorkday") == null ? null : String.valueOf(body.get("isWorkday"));
        String remark = body.get("remark") == null ? null : String.valueOf(body.get("remark"));
        return R.ok(service.upsertDate(date, isWorkday, remark));
    }

    @GetMapping("/shifts")
    @Operation(summary = "班次模板全量")
    public R<List<ShiftTemplate>> listShifts() {
        return R.ok(service.listShifts());
    }

    @PostMapping("/shifts")
    @Operation(summary = "新建班次", description = "编码唯一、工时必须 > 0")
    public R<ShiftTemplate> createShift(@RequestBody ShiftTemplate s) {
        return R.ok(service.createShift(s));
    }

    @PutMapping("/shifts/{id}")
    @Operation(summary = "修改班次", description = "编码不可改；工时必须 > 0")
    public R<ShiftTemplate> updateShift(@PathVariable String id, @RequestBody ShiftTemplate s) {
        s.setId(id);
        return R.ok(service.updateShift(s));
    }

    @DeleteMapping("/shifts/{id}")
    @Operation(summary = "删除班次", description = "被工作中心引用 422 禁止删除")
    public R<Void> deleteShift(@PathVariable String id) {
        service.deleteShift(id);
        return R.ok();
    }

    @GetMapping("/wc-shifts")
    @Operation(summary = "工作中心班次绑定", description = "wcCode 空 → 全量")
    public R<List<WcShift>> listWcShifts(@RequestParam(required = false) String wcCode) {
        return R.ok(service.listWcShifts(wcCode));
    }

    @PutMapping("/wc-shifts")
    @Operation(summary = "绑定/改绑（首期单班次替换式）")
    public R<WcShift> bindWcShift(@RequestBody Map<String, String> body) {
        return R.ok(service.bindWcShift(body.get("wcCode"), body.get("shiftCode")));
    }

    @DeleteMapping("/wc-shifts/{id}")
    @Operation(summary = "解绑")
    public R<Void> unbindWcShift(@PathVariable String id) {
        service.unbindWcShift(id);
        return R.ok();
    }
}
