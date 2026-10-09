package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.crm.OppFollowup;
import com.erp.entity.crm.OppStageLog;
import com.erp.entity.crm.Opportunity;
import com.erp.entity.system.SysNotice;
import com.erp.service.crm.OpportunityService;
import com.erp.service.system.NoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * 商机域接口（3.1.1 商机录入 / 3.1.2 商机转化 / 3.1.3 商机跟进，spec opportunity-management）。
 * 菜单 PERM = ROLE_SALES,ROLE_SALES_MGR,ROLE_ADMIN；写操作在服务层按动作再分校验。
 * 阶段推进的签署走通用审批待办（/api/qms/approvals），本控制器只提交与查询日志。
 */
@Tag(name = "商机管理")
@RestController
@RequestMapping("/api/crm")
public class OpportunityController {

    private final OpportunityService oppService;
    private final NoticeService noticeService;

    public OpportunityController(OpportunityService oppService, NoticeService noticeService) {
        this.oppService = oppService;
        this.noticeService = noticeService;
    }

    // ---------- 3.1.1 商机录入 ----------

    @GetMapping("/opportunities")
    @Operation(summary = "商机列表", description = "页顶漏斗/赢率取 /opportunities/stats")
    public R<Page<Opportunity>> page(@RequestParam(defaultValue = "1") long current,
                                     @RequestParam(defaultValue = "10") long size,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String stage,
                                     @RequestParam(required = false) String ownerId) {
        return R.ok(oppService.page(current, size, keyword, status, stage, ownerId));
    }

    @GetMapping("/opportunities/stats")
    @Operation(summary = "漏斗与转化率赢率", description = "BR-4.8-09 自动统计，不依赖人工填报")
    public R<Map<String, Object>> stats() {
        return R.ok(oppService.stats());
    }

    @GetMapping("/opportunities/{id}")
    public R<Opportunity> get(@PathVariable String id) {
        return R.ok(oppService.get(id));
    }

    @PostMapping("/opportunities")
    @Operation(summary = "创建商机", description = "手工登记 或 带 leadId 的线索转化（等级 ≥ B）")
    public R<Opportunity> create(@RequestBody Opportunity body) {
        return R.ok(oppService.create(body));
    }

    @PutMapping("/opportunities")
    public R<Opportunity> update(@RequestBody Opportunity body) {
        return R.ok(oppService.update(body));
    }

    // ---------- 3.1.3 商机跟进：阶段推进 / 跟进记录 / 丢失归档 ----------

    @PostMapping("/opportunities/{id}/advance-stage")
    @Operation(summary = "推进阶段", description = "仅下一阶段；生成销售经理审批，通过前停在原阶段")
    public R<OppStageLog> advanceStage(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String toStage = str(body.get("toStage"));
        Integer probability = body.get("probability") == null ? null : Integer.valueOf(String.valueOf(body.get("probability")));
        LocalDate nextActionDate = body.get("nextActionDate") == null || String.valueOf(body.get("nextActionDate")).isEmpty()
                ? null : LocalDate.parse(String.valueOf(body.get("nextActionDate")));
        return R.ok(oppService.advanceStage(id, toStage, probability, str(body.get("nextAction")), nextActionDate));
    }

    @PostMapping("/opportunities/{id}/lost")
    @Operation(summary = "丢失归档", description = "原因分类 + 说明必填，归档后只读")
    public R<Opportunity> markLost(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(oppService.markLost(id, str(body.get("category")), str(body.get("remark"))));
    }

    @GetMapping("/opportunities/{id}/stage-logs")
    public R<List<OppStageLog>> stageLogs(@PathVariable String id) {
        return R.ok(oppService.stageLogs(id));
    }

    @GetMapping("/opportunities/{id}/followups")
    public R<List<OppFollowup>> followups(@PathVariable String id) {
        return R.ok(oppService.followups(id));
    }

    @PostMapping("/opportunities/{id}/followups")
    @Operation(summary = "追加跟进", description = "仅追加：不提供改/删接口")
    public R<OppFollowup> addFollowup(@PathVariable String id, @RequestBody OppFollowup body) {
        body.setOppId(id);
        return R.ok(oppService.addFollowup(body));
    }

    // ---------- 3.1.2 商机转化 ----------

    @PostMapping("/opportunities/{id}/check-quote")
    @Operation(summary = "转化闸口校验", description = "BR-4.3-07：状态/客户卡控；失败回写追踪记录，成功返回报价预填数据")
    public R<Map<String, Object>> checkQuote(@PathVariable String id) {
        return R.ok(oppService.checkQuoteGate(id));
    }

    // ---------- 调度手动触发（与线索 sweep 同范式） ----------

    @PostMapping("/opportunities/sweep")
    public R<Integer> sweep() {
        return R.ok(oppService.sweepOverdue());
    }

    // ---------- 站内通知（FR-4.8-1-5 超百万通知销售经理） ----------

    @GetMapping("/notices")
    @Operation(summary = "我的通知", description = "按角色聚合，未读在前")
    public R<List<SysNotice>> notices() {
        return R.ok(noticeService.myNotices());
    }

    @GetMapping("/notices/unread-count")
    public R<Long> unreadCount() {
        return R.ok(noticeService.unreadCount());
    }

    @PutMapping("/notices/{id}/read")
    public R<Void> markRead(@PathVariable String id) {
        noticeService.markRead(id);
        return R.ok();
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
