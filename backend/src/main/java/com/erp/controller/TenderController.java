package com.erp.controller;

import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.service.proc.TenderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * 招标竞价（2.2.2，/api/proc/tenders）。
 * 权限沿用 SecurityConfig 既有规则：POST/PUT/DELETE /api/proc/** → ADMIN（写）。
 * 评标（评分提交）接口在任务 4.1 单独放开 ROLE_BID_JUDGE。
 */
@Tag(name = "招标竞价", description = "招标立项、报名与资格审查、多轮报价、开标评标、定标公示")
@RestController
@RequestMapping("/api/proc")
public class TenderController {

    private final TenderService service;

    public TenderController(TenderService service) {
        this.service = service;
    }

    @Operation(summary = "招标项目分页", description = "查询前自动执行懒 sweep（报价截止即锁价）")
    @GetMapping("/tenders")
    public R<Map<String, Object>> page(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "10") long size,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String keyword) {
        var page = service.page(current, size, status, keyword);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("records", page.getRecords());
        result.put("total", page.getTotal());
        return R.ok(result);
    }

    @Operation(summary = "招标项目详情", description = "含行、投标方与资格状态、多轮报价、评委与评分")
    @GetMapping("/tenders/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "招标立项", description = "FR-4.2-10-1：生成唯一招标编号并落行与投标方，初始状态 DRAFT")
    @PostMapping("/tenders")
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        return R.ok(service.create(payload));
    }

    @Operation(summary = "更新立项要素", description = "仅 DRAFT 可改；携带不同的招标编号将被拒绝（编号创建后不可修改）")
    @org.springframework.web.bind.annotation.PutMapping("/tenders")
    public R<Map<String, Object>> update(@RequestBody Map<String, Object> payload) {
        Object id = payload.get("id");
        if (id == null || String.valueOf(id).isBlank()) {
            throw new ServiceException(422, "id 必填");
        }
        return R.ok(service.update(String.valueOf(id), payload));
    }

    @Operation(summary = "开始报名", description = "DRAFT → BIDDING")
    @PostMapping("/tenders/{id}/start")
    public R<Map<String, Object>> start(@PathVariable String id) {
        return R.ok(service.start(id));
    }

    @Operation(summary = "资格审查", description = "PASS / FAIL，重算合格投标方数（BR-4.2-45 判定依据）")
    @PostMapping("/tenders/{id}/qualify")
    public R<Map<String, Object>> qualify(@PathVariable String id,
                                          @RequestBody Map<String, Object> body) {
        return R.ok(service.qualify(id, str(body.get("supplierId")),
                str(body.get("status")), str(body.get("note"))));
    }

    @Operation(summary = "追加投标方", description = "锁价前可追加，来源标记 INVITE，重复幂等")
    @PostMapping("/tenders/{id}/suppliers")
    public R<Map<String, Object>> addSuppliers(@PathVariable String id,
                                               @RequestBody Map<String, Object> body) {
        return R.ok(service.addSuppliers(id, strList(body.get("supplierIds"))));
    }

    @Operation(summary = "延长报名期", description = "BR-4.2-45 入口一：新截止须更晚并填写原因留痕")
    @PostMapping("/tenders/{id}/postpone-reg")
    public R<Map<String, Object>> postponeReg(@PathVariable String id,
                                              @RequestBody Map<String, Object> body) {
        return R.ok(service.postponeReg(id, str(body.get("newDeadline")), str(body.get("reason"))));
    }

    @Operation(summary = "转邀请招标", description = "BR-4.2-45 入口二：升级采购总监审批（INVITE_PENDING=1）")
    @PostMapping("/tenders/{id}/to-invite")
    public R<Map<String, Object>> toInvite(@PathVariable String id,
                                           @RequestBody Map<String, Object> body) {
        return R.ok(service.toInvite(id, str(body.get("reason"))));
    }

    @Operation(summary = "转邀请招标审批", description = "BR-4.2-45 L2：采购总监通过=2，驳回=3 并回退公开招标")
    @PostMapping("/tenders/{id}/invite-approval")
    public R<Map<String, Object>> inviteApproval(@PathVariable String id,
                                                 @RequestBody Map<String, Object> body) {
        Object ap = body.get("approved");
        boolean approved = ap != null && Boolean.parseBoolean(String.valueOf(ap));
        return R.ok(service.inviteApproval(id, approved, str(body.get("reason"))));
    }

    @Operation(summary = "多轮报价录入", description = "BR-4.2-06：轮次自增，历史轮次只读，本轮价不得高于上轮价")
    @PostMapping("/tenders/{id}/quotes")
    public R<Map<String, Object>> saveQuote(@PathVariable String id,
                                            @RequestBody Map<String, Object> payload) {
        return R.ok(service.saveQuote(id, payload));
    }

    @Operation(summary = "修改历史报价", description = "历史轮次只读，固定返回 422（BR-4.2-06）")
    @org.springframework.web.bind.annotation.PutMapping("/tenders/quotes/{quoteId}")
    public R<Map<String, Object>> updateQuote(@PathVariable String quoteId,
                                              @RequestBody Map<String, Object> payload) {
        return R.ok(service.updateQuote(quoteId, payload));
    }

    @Operation(summary = "延长报价截止", description = "仅锁价前；新截止须更晚并填写原因留痕")
    @PostMapping("/tenders/{id}/postpone")
    public R<Map<String, Object>> postpone(@PathVariable String id,
                                           @RequestBody Map<String, Object> body) {
        return R.ok(service.postpone(id, str(body.get("newDeadline")), str(body.get("reason"))));
    }

    @Operation(summary = "开标", description = "LOCKED → EVALUATING；BR-4.2-45 合格投标方不足时阻断")
    @PostMapping("/tenders/{id}/open")
    public R<Map<String, Object>> open(@PathVariable String id) {
        return R.ok(service.open(id));
    }

    @Operation(summary = "我的评标任务", description = "仅返回当前评委本人的评分任务，不含其他评委数据（4.2）")
    @GetMapping("/tenders/{id}/my-tasks")
    public R<Map<String, Object>> myTasks(@PathVariable String id) {
        return R.ok(service.myTasks(id));
    }

    @Operation(summary = "指定评标委员", description = "替换式；评标开始后不可更换（design D5）")
    @PostMapping("/tenders/{id}/judges")
    public R<Map<String, Object>> setJudges(@PathVariable String id,
                                            @RequestBody Map<String, Object> body) {
        return R.ok(service.setJudges(id, strList(body.get("judgeUserIds"))));
    }

    @Operation(summary = "评委提交评分", description = "FR-4.2-10-2：4 维加权；仅评委本人；提交后锁定（BR-4.2-47）")
    @PostMapping("/tenders/{id}/scores")
    public R<Map<String, Object>> saveScore(@PathVariable String id,
                                            @RequestBody Map<String, Object> payload) {
        return R.ok(service.saveScore(id, payload));
    }

    @Operation(summary = "合规审批后修改评分", description = "BR-4.2-47：阻断直接修改，改经本入口并留痕新旧值")
    @PostMapping("/tenders/{id}/scores/amend")
    public R<Map<String, Object>> amendScore(@PathVariable String id,
                                             @RequestBody Map<String, Object> body) {
        Object sid = body.get("scoreId");
        if (sid == null || String.valueOf(sid).isBlank()) {
            throw new ServiceException(422, "scoreId 必填");
        }
        return R.ok(service.amendScore(id, String.valueOf(sid), body));
    }

    @Operation(summary = "评标汇总定标", description = "最低价法取最低有效报价；综合评分法取各评委加权分算术平均最高者")
    @PostMapping("/tenders/{id}/evaluate")
    public R<Map<String, Object>> evaluate(@PathVariable String id) {
        return R.ok(service.evaluate(id));
    }

    @Operation(summary = "异常标记/解除", description = "偏差 D6：人工标记围标串标并冻结评标，解除后恢复")
    @PostMapping("/tenders/{id}/anomaly")
    public R<Map<String, Object>> setAnomaly(@PathVariable String id,
                                             @RequestBody Map<String, Object> body) {
        Object f = body.get("anomaly");
        boolean anomaly = f != null && Boolean.parseBoolean(String.valueOf(f));
        return R.ok(service.setAnomaly(id, anomaly, str(body.get("note"))));
    }

    @Operation(summary = "定标审批待办", description = "design D6 单节点；返回 PENDING_AWARD 招标与路由链 [采购总监]")
    @GetMapping("/tenders/approvals/todo")
    public R<java.util.List<Map<String, Object>>> approvalTodo() {
        return R.ok(service.approvalTodo());
    }

    @Operation(summary = "定标录入中标人与份额", description = "多中标人：≥1 家合格投标方、单价=最终轮报价、Σ份额=100（design D2）")
    @PostMapping("/tenders/{id}/award-winners")
    public R<Map<String, Object>> saveWinners(@PathVariable String id,
                                               @RequestBody Map<String, Object> body) {
        Object ws = body.get("winners");
        if (!(ws instanceof java.util.List)) {
            return R.fail(422, "winners 必填（数组）");
        }
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> list = (java.util.List<Map<String, Object>>) ws;
        return R.ok(service.saveWinners(id, list));
    }

    @Operation(summary = "定标审批", description = "FR-4.2-10-3：通过进入公示并按 PUBLICITY_DAYS 计算起止；驳回回评标")
    @PostMapping("/tenders/{id}/approve-award")    public R<Map<String, Object>> approveAward(@PathVariable String id,
                                               @RequestBody Map<String, Object> body) {
        Object ap = body.get("approved");
        boolean approved = ap != null && Boolean.parseBoolean(String.valueOf(ap));
        return R.ok(service.approveAward(id, approved, str(body.get("note"))));
    }

    @Operation(summary = "登记公示异议", description = "BR-4.2-48：valid=true 暂停协议生成并冻结定标（偏差 D4 内部代录）")
    @PostMapping("/tenders/{id}/objections")
    public R<Map<String, Object>> raiseObjection(@PathVariable String id,
                                                 @RequestBody Map<String, Object> body) {
        Object v = body.get("valid");
        boolean valid = v == null || Boolean.parseBoolean(String.valueOf(v));
        return R.ok(service.raiseObjection(id, str(body.get("content")), valid));
    }

    @Operation(summary = "异议复核裁定", description = "BR-4.2-48：MAINTAIN 恢复公示 / REBID 作废原定标")
    @PostMapping("/tenders/{id}/objections/{objId}/review")
    public R<Map<String, Object>> reviewObjection(@PathVariable String id,
                                                  @PathVariable String objId,
                                                  @RequestBody Map<String, Object> body) {
        return R.ok(service.reviewObjection(id, objId, str(body.get("verdict")), str(body.get("note"))));
    }

    @Operation(summary = "手工创建协议", description = "design D5：创建即生效（status=1），Σ份额=100，区间成对校验")
    @PostMapping("/framework-agreements")
    public R<Map<String, Object>> createAgreement(@RequestBody Map<String, Object> payload) {
        return R.ok(service.createAgreement(payload));
    }

    @Operation(summary = "协议续签", description = "仅临期/已到期可续签：生成新协议，原协议置已到期（L1059）")
    @PostMapping("/framework-agreements/{id}/renew")
    public R<Map<String, Object>> renewAgreement(@PathVariable String id) {
        return R.ok(service.renewAgreement(id));
    }

    @Operation(summary = "协议终止", description = "仅生效中/临期可终止，原因必填，置 4 后不可逆")
    @PostMapping("/framework-agreements/{id}/stop")
    public R<Map<String, Object>> stopAgreement(@PathVariable String id,
                                                 @RequestBody Map<String, Object> body) {
        return R.ok(service.stopAgreement(id, str(body.get("reason"))));
    }

    @Operation(summary = "框架协议列表", description = "按协议号/招标号/状态检索（2.2.3）")
    @GetMapping("/framework-agreements")    public R<java.util.List<Map<String, Object>>> agreements(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return R.ok(service.agreements(keyword, status));
    }

    @Operation(summary = "框架协议详情", description = "头 + 明细行（中标单价、份额、有效期）")
    @GetMapping("/framework-agreements/{id}")
    public R<Map<String, Object>> agreementDetail(@PathVariable String id) {
        return R.ok(service.agreementDetail(id));
    }

    @Operation(summary = "直接修改协议明细", description = "FR-4.2-10-3 价格份额锁定，固定返回 422")
    @org.springframework.web.bind.annotation.PutMapping("/framework-agreements/lines/{lineId}")
    public R<Map<String, Object>> updateAgreementLine(@PathVariable String lineId,
                                                      @RequestBody Map<String, Object> payload) {
        return R.ok(service.updateAgreementLine(lineId, payload));
    }

    @Operation(summary = "协议变更（经审批）", description = "允许修改单价/份额，留痕变更前后值、操作人与时间")
    @PostMapping("/framework-agreements/{id}/lines/{lineId}/change")
    public R<Map<String, Object>> changeAgreementLine(@PathVariable String id,
                                                      @PathVariable String lineId,
                                                      @RequestBody Map<String, Object> payload) {
        return R.ok(service.changeAgreementLine(id, lineId, payload));
    }

    @Operation(summary = "作废招标", description = "锁价前各阶段可作废；已生成框架协议则 422 拒绝")
    @PostMapping("/tenders/{id}/cancel")
    public R<Void> cancel(@PathVariable String id, @RequestBody Map<String, Object> body) {
        service.cancel(id, str(body.get("reason")));
        return R.ok();
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private List<String> strList(Object o) {
        if (!(o instanceof List)) {
            throw new ServiceException(422, "supplierIds 必填");
        }
        List<String> out = new java.util.ArrayList<>();
        for (Object x : (List<Object>) o) {
            if (x != null && !String.valueOf(x).isBlank()) {
                out.add(String.valueOf(x).trim());
            }
        }
        return out;
    }
}
