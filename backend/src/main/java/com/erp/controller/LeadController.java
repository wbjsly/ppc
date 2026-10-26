package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.crm.Lead;
import io.swagger.v3.oas.annotations.Operation;
import com.erp.entity.crm.LeadFollowup;
import com.erp.entity.crm.LeadPool;
import com.erp.entity.crm.LeadScoreModel;
import com.erp.entity.crm.CrmAssignRule;
import com.erp.entity.crm.CrmSalesProfile;
import com.erp.service.crm.LeadAutoAssignService;
import com.erp.service.crm.LeadScoreModelService;
import com.erp.service.crm.LeadService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 线索域接口（11.1.1 线索录入 / 11.1.2 线索评分，spec crm-lead-management）。
 * 菜单 PERM = ROLE_SALES,ROLE_SALES_MGR,ROLE_ADMIN；写操作在服务层按动作再分校验。
 */
@RestController
@RequestMapping("/api/crm")
public class LeadController {

    private final LeadService leadService;
    private final LeadScoreModelService modelService;
    private final LeadAutoAssignService autoAssignService;

    public LeadController(LeadService leadService, LeadScoreModelService modelService,
                           LeadAutoAssignService autoAssignService) {
        this.leadService = leadService;
        this.modelService = modelService;
        this.autoAssignService = autoAssignService;
    }

    // ---------- 线索 ----------

    @GetMapping("/leads")
    public R<Page<Lead>> page(@RequestParam(defaultValue = "1") long current,
                              @RequestParam(defaultValue = "10") long size,
                              @RequestParam(required = false) String keyword,
                              @RequestParam(required = false) String status,
                              @RequestParam(required = false) String grade,
                              @RequestParam(required = false) String ownerId) {
        return R.ok(leadService.page(current, size, keyword, status, grade, ownerId));
    }

    @GetMapping("/leads/{id}")
    public R<Lead> get(@PathVariable String id) {
        return R.ok(leadService.get(id));
    }

    /** 查重（FR-4.8-1-1）：返回既有线索候选，页面 L4 提示并可关联 */
    @PostMapping("/leads/check-duplicate")
    public R<List<Lead>> checkDuplicate(@RequestBody Lead body) {
        return R.ok(leadService.checkDuplicate(body.getCompanyName(), body.getContactName()));
    }

    @PostMapping("/leads")
    public R<Lead> create(@RequestBody Lead lead) {
        return R.ok(leadService.create(lead));
    }

    @PutMapping("/leads")
    public R<Lead> update(@RequestBody Lead lead) {
        return R.ok(leadService.update(lead));
    }

    /** 分配 / 改派（销售经理） */
    @PutMapping("/leads/{id}/assign")
    public R<Void> assign(@PathVariable String id,
                          @RequestParam String ownerId,
                          @RequestParam(required = false) String ownerName) {
        leadService.assign(id, ownerId, ownerName);
        return R.ok();
    }

    /** 认领（BR-4.8-08 单人锁定：他人已负责则拒绝） */
    @PutMapping("/leads/{id}/claim")
    public R<Void> claim(@PathVariable String id) {
        leadService.claim(id);
        return R.ok();
    }

    /** 手工入池（D 级由评分自动入池，此处为经理手工操作） */
    @PutMapping("/leads/{id}/pool")
    public R<Void> toPool(@PathVariable String id,
                          @RequestParam String reason,
                          @RequestParam(required = false) String remark) {
        leadService.toPool(id, reason, remark);
        return R.ok();
    }

    /** 五维评分（缺项按 0 分计并返回缺失维度，L4 提示） */
    @PostMapping("/leads/{id}/score")
    public R<Map<String, Object>> score(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(leadService.score(id,
                intOf(body.get("need")), intOf(body.get("budget")), intOf(body.get("chain")),
                intOf(body.get("urgency")), intOf(body.get("compete"))));
    }

    // ---------- 跟进 ----------

    @GetMapping("/leads/{id}/followups")
    public R<List<LeadFollowup>> followups(@PathVariable String id) {
        return R.ok(leadService.followups(id));
    }

    @PostMapping("/leads/{id}/followups")
    public R<LeadFollowup> addFollowup(@PathVariable String id, @RequestBody LeadFollowup body) {
        body.setLeadId(id);
        return R.ok(leadService.addFollowup(body));
    }

    // ---------- 线索池 ----------

    @GetMapping("/lead-pool")
    public R<List<LeadPool>> pool(@RequestParam(required = false) String status) {
        return R.ok(leadService.poolList(status));
    }

    @PutMapping("/lead-pool/{id}/assign")
    public R<Void> assignFromPool(@PathVariable String id,
                                  @RequestParam String ownerId,
                                  @RequestParam(required = false) String ownerName) {
        leadService.assignFromPool(id, ownerId, ownerName);
        return R.ok();
    }

    /** 手工触发调度（测试与补跑用；定时任务另有每日 08:00 执行） */
    @PostMapping("/leads/sweep")
    public R<Map<String, Integer>> sweep() {
        return R.ok(leadService.sweep());
    }

    // ---------- 评分模型（11.1.2 模型配置 Tab） ----------

    @GetMapping("/lead-models")
    public R<List<LeadScoreModel>> models(@RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String industry) {
        return R.ok(modelService.list(keyword, industry));
    }

    @GetMapping("/lead-models/active")
    public R<LeadScoreModel> activeModel(@RequestParam(required = false) String industry,
                                         @RequestParam(required = false) String productLine) {
        return R.ok(modelService.active(industry, productLine));
    }

    @GetMapping("/lead-models/{key}/versions")
    public R<List<LeadScoreModel>> versions(@PathVariable String key) {
        return R.ok(modelService.versions(key));
    }

    @PostMapping("/lead-models")
    public R<LeadScoreModel> saveModel(@RequestBody LeadScoreModel model) {
        return R.ok(modelService.save(model));
    }

    private Integer intOf(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : Integer.parseInt(s);
    }

    // ---------- 自动分配配置（add-lead-auto-assign，FR-4.8-1-3；11.1.2「分配规则」Tab） ----------

    @GetMapping("/assign/seniors")
    @Operation(summary = "资深销售名单（含全部已有档案行，便于 Tab 展示勾选态）")
    public R<List<CrmSalesProfile>> seniorList() {
        return R.ok(autoAssignService.seniorList());
    }

    @PutMapping("/assign/seniors")
    @PreAuthorize("hasAnyRole('SALES_MGR','ADMIN')")
    @Operation(summary = "批量保存资深名单（全量覆盖：未勾选的既有标记清除）")
    public R<Void> saveSeniors(@RequestBody List<Map<String, String>> seniors) {
        autoAssignService.saveSeniors(seniors);
        return R.ok(null);
    }

    @GetMapping("/assign/rules")
    @Operation(summary = "分配规则清单（优先级升序）")
    public R<List<CrmAssignRule>> ruleList() {
        return R.ok(autoAssignService.ruleList());
    }

    @PostMapping("/assign/rules")
    @PreAuthorize("hasAnyRole('SALES_MGR','ADMIN')")
    @Operation(summary = "新建规则（目标销售集为空 422；区域/行业空=通配）")
    public R<CrmAssignRule> createRule(@RequestBody CrmAssignRule rule) {
        rule.setId(null);
        return R.ok(autoAssignService.saveRule(rule));
    }

    @PutMapping("/assign/rules/{id}")
    @PreAuthorize("hasAnyRole('SALES_MGR','ADMIN')")
    @Operation(summary = "更新规则")
    public R<CrmAssignRule> updateRule(@PathVariable String id, @RequestBody CrmAssignRule rule) {
        rule.setId(id);
        return R.ok(autoAssignService.saveRule(rule));
    }

    @DeleteMapping("/assign/rules/{id}")
    @PreAuthorize("hasAnyRole('SALES_MGR','ADMIN')")
    @Operation(summary = "删除规则（逻辑删）")
    public R<Void> deleteRule(@PathVariable String id) {
        autoAssignService.deleteRule(id);
        return R.ok(null);
    }
}
