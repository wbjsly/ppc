package com.erp.controller;

import com.erp.common.R;
import com.erp.service.pm.PmServiceOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 服务工单（add-service-order，M11.10）。
 * 11.10.1 投诉受理（登记/定级/派单）/ 11.10.2 工单闭环（处理/确认/回访/改进任务）。
 * 匿名确认端点另见 /api/public/service-orders/**（SecurityConfig 白名单）。
 */
@Tag(name = "服务工单")
@RestController
@RequestMapping("/api/pm/service-orders")
@RequiredArgsConstructor
public class PmServiceOrderController {

    private final PmServiceOrderService service;

    // ==================== 11.10.1 投诉受理 ====================

    @PostMapping
    @Operation(summary = "投诉登记（必填 L1、自动定级、重复投诉候选）")
    public R<Map<String, Object>> register(@RequestBody Map<String, Object> payload) {
        return R.ok(service.register(payload));
    }

    @GetMapping
    @Operation(summary = "工单分页（客户/等级/状态/工程师/关键字）")
    public R<Map<String, Object>> page(@RequestParam(required = false) String customerId,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String engineerId,
                                       @RequestParam(required = false) String level,
                                       @RequestParam(required = false) String keyword) {
        return R.ok(service.page(Map.of(
                "customerId", nvl(customerId), "status", nvl(status),
                "engineerId", nvl(engineerId), "level", nvl(level), "keyword", nvl(keyword))));
    }

    @GetMapping("/engineers/recommend")
    @Operation(summary = "负载推荐服务工程师（在手工单最少优先）")
    public R<Map<String, Object>> recommendEngineer() {
        return R.ok(service.recommendEngineer());
    }

    @PutMapping("/{id}/duplicate")
    @Operation(summary = "关联为重复投诉（duplicate_of，SLA 独立计时）")
    public R<Map<String, Object>> markDuplicate(@PathVariable String id,
                                                @RequestBody Map<String, Object> payload) {
        return R.ok(service.markDuplicate(id, payload));
    }

    @PutMapping("/{id}/level")
    @Operation(summary = "人工调整定级（理由必填，L5 留痕）")
    public R<Map<String, Object>> adjustLevel(@PathVariable String id,
                                              @RequestBody Map<String, Object> payload) {
        return R.ok(service.adjustLevel(id, payload));
    }

    @PutMapping("/{id}/dispatch")
    @Operation(summary = "派单/改派（负载推荐可覆盖；空=待指派升级主管）")
    public R<Map<String, Object>> dispatch(@PathVariable String id,
                                           @RequestBody(required = false) Map<String, Object> payload) {
        return R.ok(service.dispatch(id, payload == null ? Map.of() : payload));
    }

    // ==================== 11.10.2 工单闭环 ====================

    @GetMapping("/{id}")
    @Operation(summary = "详情（头+处理记录+回访+确认 token+改进任务）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/{id}/accept")
    @Operation(summary = "工程师受理（响应计时终点）")
    public R<Map<String, Object>> accept(@PathVariable String id) {
        return R.ok(service.accept(id));
    }

    @PostMapping("/{id}/records")
    @Operation(summary = "追加处理记录（BR-4.8-35 仅追加）")
    public R<Map<String, Object>> appendProcess(@PathVariable String id,
                                                @RequestBody Map<String, Object> payload) {
        return R.ok(service.appendProcess(id, payload));
    }

    @PutMapping("/{id}/root-cause")
    @Operation(summary = "保存根因分类（未填不可提交关闭；设计缺陷抄送研发）")
    public R<Map<String, Object>> saveRootCause(@PathVariable String id,
                                                @RequestBody Map<String, Object> payload) {
        return R.ok(service.saveRootCause(id, payload));
    }

    @PutMapping("/{id}/ncr")
    @Operation(summary = "关联 NCR（存在性校验；质量类必关联，BR-4.8-36）")
    public R<Map<String, Object>> linkNcr(@PathVariable String id,
                                          @RequestBody Map<String, Object> payload) {
        return R.ok(service.linkNcr(id, payload));
    }

    @PostMapping("/{id}/cured")
    @Operation(summary = "标记已根治（质量类无 NCR → 422）")
    public R<Map<String, Object>> markCured(@PathVariable String id) {
        return R.ok(service.markCured(id));
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "挂起「待客户信息」（SLA 暂停计时）")
    public R<Map<String, Object>> suspend(@PathVariable String id,
                                          @RequestBody(required = false) Map<String, Object> payload) {
        return R.ok(service.suspend(id, payload == null ? Map.of() : payload));
    }

    @PostMapping("/{id}/resume")
    @Operation(summary = "恢复处理（累计挂起时长不计 SLA）")
    public R<Map<String, Object>> resume(@PathVariable String id) {
        return R.ok(service.resume(id));
    }

    @PostMapping("/{id}/submit-confirm")
    @Operation(summary = "提交客户确认（根因未填 422）")
    public R<Map<String, Object>> submitConfirm(@PathVariable String id) {
        return R.ok(service.submitConfirm(id));
    }

    @PostMapping("/{id}/agent-confirm")
    @Operation(summary = "代录确认/驳回（confirmed_via=AGENT；驳回重计时）")
    public R<Map<String, Object>> agentConfirm(@PathVariable String id,
                                               @RequestBody Map<String, Object> payload) {
        return R.ok(service.agentConfirm(id, payload));
    }

    @PostMapping("/{id}/confirm-link")
    @Operation(summary = "生成客户确认免密链接（token 一次性）")
    public R<Map<String, Object>> generateConfirmLink(@PathVariable String id) {
        return R.ok(service.generateConfirmLink(id));
    }

    @PutMapping("/{id}/survey")
    @Operation(summary = "满意度回访（≤2 分自动生成改进任务）")
    public R<Map<String, Object>> saveSurvey(@PathVariable String id,
                                             @RequestBody Map<String, Object> payload) {
        return R.ok(service.saveSurvey(id, payload));
    }

    @GetMapping("/surveys/stats")
    @Operation(summary = "回访统计三维汇总（客户/工程师/类型）")
    public R<Map<String, Object>> surveyStats(@RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to) {
        return R.ok(service.surveyStats(Map.of("from", nvl(from), "to", nvl(to))));
    }

    @GetMapping("/improve-tasks")
    @Operation(summary = "改进任务列表（BR-4.8-06）")
    public R<Map<String, Object>> improveTasks(@RequestParam(required = false) String status) {
        return R.ok(service.improveTasks(Map.of("status", nvl(status))));
    }

    @PutMapping("/improve-tasks/{id}")
    @Operation(summary = "改进任务状态流转（PENDING→PROCESSING→CLOSED）")
    public R<Map<String, Object>> updateImproveTask(@PathVariable String id,
                                                    @RequestBody Map<String, Object> payload) {
        return R.ok(service.updateImproveTask(id, payload));
    }

    @PostMapping("/sweep")
    @Operation(summary = "手动触发每日扫描（ADMIN 运维端点：升级/默认关闭/回访提醒/改进逾期）")
    public R<Map<String, Object>> sweep() {
        return R.ok(service.sweep());
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
