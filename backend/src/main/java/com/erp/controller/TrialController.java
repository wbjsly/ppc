package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.TrialApply;
import com.erp.entity.mrp.TrialData;
import com.erp.entity.mrp.TrialSummary;
import com.erp.service.mrp.TrialApplyService;
import com.erp.service.mrp.TrialDataService;
import com.erp.service.mrp.TrialOrderService;
import com.erp.service.mrp.TrialSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Getter;
import lombok.Setter;
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
 * 试产管理 5.9（change add-trial-production-management，spec trial-order / trial-execution）。
 * 三叶菜单（5.9.1 EVT / 5.9.2 DVT / 5.9.3 PVT）共用本端点组，页面按 stage 参数过滤。
 * 权限校验在服务层（design D12：申请/转开/采集/总结 = 计划+工艺+PM 六角色面；
 * 试产版审批 TrialBom/TrialRoute/TrialPvtDiff 签署节点 = PROCESS_MGR；
 * 挪用 TrialBorrow 签 = PROCESS_MGR；冲回 TrialCostReverse 签 = FINANCE_MGR——
 * 后三者经审批底座节点角色强制，均不在本控制器放行）。
 */
@Tag(name = "5.9 试产管理")
@RestController
@RequestMapping("/api/mrp/trial")
public class TrialController {

    private final TrialApplyService applyService;
    private final TrialOrderService orderService;
    private final TrialDataService dataService;
    private final TrialSummaryService summaryService;

    public TrialController(TrialApplyService applyService, TrialOrderService orderService,
                           TrialDataService dataService, TrialSummaryService summaryService) {
        this.applyService = applyService;
        this.orderService = orderService;
        this.dataService = dataService;
        this.summaryService = summaryService;
    }

    // ================= 试产申请单（trial-order FR-4.5-8-1/2） =================

    @Getter
    @Setter
    public static class ApplyRequest {
        private String trialStage;
        private String productCode;
        private java.math.BigDecimal qty;
        private String goal;
    }

    @PostMapping("/apply/create")
    @Operation(summary = "试产申请建档（阶段/数量/验证目标校验；阶段闸口 DVT←EVT、PVT←DVT）")
    public R<TrialApply> createApply(@RequestBody ApplyRequest req) {
        TrialApply head = new TrialApply();
        head.setTrialStage(req.getTrialStage());
        head.setProductCode(req.getProductCode());
        head.setQty(req.getQty());
        head.setGoal(req.getGoal());
        return R.ok(applyService.create(head));
    }

    @PostMapping("/apply/submit")
    @Operation(summary = "提交申请审批（工艺主管单节点；驳回回草稿可重提）")
    public R<TrialApply> submitApply(@RequestBody Map<String, String> body) {
        return R.ok(applyService.submit(body.get("trialNo")));
    }

    @GetMapping("/apply/list")
    @Operation(summary = "申请单列表（阶段/状态/产品过滤）")
    public R<List<TrialApply>> applyList(@RequestParam(required = false) String trialStage,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String productCode) {
        return R.ok(applyService.list(trialStage, status, productCode));
    }

    // ================= 转开与工单（trial-order FR-4.5-8-3 / C-4.5-14） =================

    @Getter
    @Setter
    public static class ConvertRequest {
        private String trialNo;
        private String bomId;
        private String routingId;
        private boolean ackFormalBom;
    }

    @PostMapping("/convert")
    @Operation(summary = "1:1 转开 TP- 工单（C-4.5-14 卡控链 + PVT diff 审批）")
    public R<Map<String, Object>> convert(@RequestBody ConvertRequest req) {
        return R.ok(orderService.createFromApply(req.getTrialNo(), req.getBomId(),
                req.getRoutingId(), req.isAckFormalBom()));
    }

    @GetMapping("/list")
    @Operation(summary = "试产工单列表（TRIAL 分池；阶段/状态过滤）")
    public R<List<MrpMo>> list(@RequestParam(required = false) String trialStage,
                               @RequestParam(required = false) String status) {
        return R.ok(orderService.list(trialStage, status));
    }

    @GetMapping("/phase/{moId}")
    @Operation(summary = "生命周期阶段推导（执行中/待检验/待入库/待评审/待关闭/终态）")
    public R<String> phase(@PathVariable String moId) {
        return R.ok(orderService.phaseOf(moId));
    }

    // ================= 数据采集（trial-execution FR-4.5-8-5） =================

    @Getter
    @Setter
    public static class DataRequest {
        private String moId;
        private Integer opSeq;
        private java.math.BigDecimal actualHours;
        private String params;
        private String defectModes;
        private String remark;
    }

    @GetMapping("/data/form")
    @Operation(summary = "采集表单（带出 5.7 CONFIRMED 报工工时；工序校验）")
    public R<Map<String, Object>> dataForm(@RequestParam String moId, @RequestParam Integer opSeq) {
        return R.ok(dataService.form(moId, opSeq));
    }

    @PostMapping("/data/save")
    @Operation(summary = "采集 upsert（按工单+工序；缺记录阻断结案在关单钩子）")
    public R<TrialData> saveData(@RequestBody DataRequest req) {
        TrialData row = new TrialData();
        row.setMoId(req.getMoId());
        row.setOpSeq(req.getOpSeq());
        row.setActualHours(req.getActualHours());
        row.setParams(req.getParams());
        row.setDefectModes(req.getDefectModes());
        row.setRemark(req.getRemark());
        return R.ok(dataService.save(row));
    }

    @GetMapping("/data/{moId}")
    @Operation(summary = "工单采集行列表")
    public R<List<TrialData>> dataList(@PathVariable String moId) {
        return R.ok(dataService.listByMo(moId));
    }

    // ================= 总结与评审（trial-execution FR-4.5-8-6） =================

    @Getter
    @Setter
    public static class SummaryRequest {
        private String moId;
        private String issues;
    }

    @PostMapping("/summary/save")
    @Operation(summary = "总结建档/刷新（三指标服务端计算，客户端指标字段忽略）")
    public R<TrialSummary> saveSummary(@RequestBody SummaryRequest req) {
        TrialSummary head = new TrialSummary();
        head.setMoId(req.getMoId());
        head.setIssues(req.getIssues());
        return R.ok(summaryService.save(head));
    }

    @PostMapping("/summary/submit")
    @Operation(summary = "提交跨部门评审（工艺/质量/计划三并行会签；PVT 良率闸口前置）")
    public R<TrialSummary> submitSummary(@RequestBody Map<String, String> body) {
        return R.ok(summaryService.submit(body.get("summaryNo")));
    }

    @GetMapping("/summary/list")
    @Operation(summary = "总结列表（阶段/状态过滤）")
    public R<List<TrialSummary>> summaryList(@RequestParam(required = false) String trialStage,
                                             @RequestParam(required = false) String status) {
        return R.ok(summaryService.list(trialStage, status));
    }

    // ================= 转量产与成本关单（trial-execution FR-4.5-8-7/8） =================

    @PostMapping("/promote")
    @Operation(summary = "转量产复制（评审 APPROVED 才可转；克隆量产草稿记 SRC_TRIAL_NO）")
    public R<Map<String, Object>> promote(@RequestBody Map<String, String> body) {
        return R.ok(orderService.promote(body.get("moId")));
    }

    @GetMapping("/cost/{moId}")
    @Operation(summary = "试产成本明细与汇总（net = 归集 − 已确认冲回；PENDING 剔除）")
    public R<Map<String, Object>> cost(@PathVariable String moId) {
        return R.ok(orderService.costOf(moId));
    }

    @GetMapping("/remainder/{moId}")
    @Operation(summary = "剩余物料明细（POSTED 领料 × TRIAL_FLAG=1 批次 × 可用余量）")
    public R<List<Map<String, Object>>> remainder(@PathVariable String moId) {
        return R.ok(orderService.remainderOf(moId));
    }

    @PostMapping("/remainder/ack")
    @Operation(summary = "剩余物料确认留痕（关单 L2 确认闸；notice BIZ=TRIAL_REMAINDER_ACK）")
    public R<Map<String, Object>> ackRemainder(@RequestBody Map<String, String> body) {
        return R.ok(orderService.ackRemainder(body.get("moId"), body.get("note")));
    }
}
