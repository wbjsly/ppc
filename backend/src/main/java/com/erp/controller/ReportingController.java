package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.mrp.MrpFeed;
import com.erp.entity.mrp.MrpOutput;
import com.erp.entity.mrp.MrpReporting;
import com.erp.service.mrp.ReportingService;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 报工接口（change add-reporting-management，spec shop-floor-reporting，菜单 5.7.1/5.7.2）。
 * 权限（service 层校验）：录入类（open/feed/output/hours/confirm）= SHOP_FLOOR/ADMIN；
 * 管理类（冲销申请、超产审批、工时确认、完工）= PLANNER/PLAN_MGR/ADMIN。
 */
@Tag(name = "报工管理")
@RestController
@RequestMapping("/api/mrp/reporting")
public class ReportingController {

    private final ReportingService service;
    private final com.erp.service.mrp.ReportingStatsService statsService;

    public ReportingController(ReportingService service,
                               com.erp.service.mrp.ReportingStatsService statsService) {
        this.service = service;
        this.statsService = statsService;
    }

    @PostMapping("/open")
    @Operation(summary = "开工并创建报工单", description = "FR-4.5-5-1：终态阻断、超期黄标、人单代报留痕")
    public R<MrpReporting> open(@RequestBody Map<String, String> body) {
        return R.ok(service.open(body.get("dispatchId"), body.get("mode")));
    }

    @PostMapping("/confirm/{id}")
    @Operation(summary = "提交报工（确认）", description = "OPEN→CONFIRMED CAS；超产/工时异常前置校验；重复确认 409")
    public R<MrpReporting> confirm(@PathVariable String id) {
        return R.ok(service.confirm(id));
    }

    @PostMapping("/discard/{id}")
    @Operation(summary = "放弃报工草稿", description = "仅 OPEN 可删；CONFIRMED 422（BR-4.5-25 仅可冲销）")
    public R<Void> discard(@PathVariable String id) {
        service.discard(id);
        return R.ok(null);
    }

    @GetMapping("/detail/{id}")
    @Operation(summary = "报工单详情")
    public R<MrpReporting> detail(@PathVariable String id) {
        return R.ok(service.get(id));
    }

    @GetMapping("/list")
    @Operation(summary = "报工记录查询（5.7.2 管理台）", description = "工单/工序/状态筛选，创建时间倒序")
    public R<List<MrpReporting>> list(@RequestParam(required = false) String moId,
                                      @RequestParam(required = false) Integer opSeq,
                                      @RequestParam(required = false) String status) {
        return R.ok(service.list(moId, opSeq, status));
    }

    @GetMapping("/totals")
    @Operation(summary = "工序累计（良率口径）", description = "confirmedTotals：goodQty/ngQty/inputQty/rptCount（CONFIRMED 单，REVERSED 剔除）")
    public R<Map<String, Object>> totals(@RequestParam String moId,
                                         @RequestParam(required = false) Integer opSeq) {
        return R.ok(service.confirmedTotals(moId, opSeq));
    }

    @GetMapping("/yield")
    @Operation(summary = "工序良率与超阈值判定", description = "ReportingStatsService 唯一口径：actualYield/standardYield/deviation/exceeded（YIELD_TOLERANCE）")
    public R<Map<String, Object>> yield(@RequestParam String moId,
                                        @RequestParam(required = false) Integer opSeq) {
        Map<String, Object> out = new java.util.LinkedHashMap<>(statsService.yieldOf(moId, opSeq));
        out.putAll(statsService.deviationExceeded(moId, opSeq));
        return R.ok(out);
    }

    // ---------- 投料（FR-4.5-5-2） ----------

    @PostMapping("/feed")
    @Operation(summary = "投料行保存并即扣", description = "AVAILABLE_QTY −= qty，余量护栏 422；仅 OPEN")
    public R<MrpFeed> feed(@RequestBody Map<String, Object> body) {
        Object ack = body.get("overAck");
        boolean overAck = ack instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(ack));
        return R.ok(service.addFeed((String) body.get("rptId"), (String) body.get("itemCode"),
                (String) body.get("batchCode"), dec(body.get("qty")), overAck));
    }

    @GetMapping("/feeds/{rptId}")
    @Operation(summary = "投料行列表")
    public R<List<MrpFeed>> feeds(@PathVariable String rptId) {
        return R.ok(service.feedsOf(rptId));
    }

    // ---------- 产出（FR-4.5-5-4） ----------

    @PostMapping("/output")
    @Operation(summary = "产出记录与超产分级", description = "阈值内 ack 入账；超阈值挂 OverProd 审批（confirm 前置阻断）")
    public R<MrpOutput> output(@RequestBody Map<String, Object> body) {
        boolean ack = Boolean.parseBoolean(String.valueOf(body.getOrDefault("overprodAck", "false")));
        return R.ok(service.saveOutput((String) body.get("rptId"), dec(body.get("goodQty")),
                dec(body.get("ngQty")), (String) body.get("ngDispose"), ack));
    }

    @GetMapping("/output/{rptId}")
    @Operation(summary = "产出行")
    public R<MrpOutput> outputOf(@PathVariable String rptId) {
        return R.ok(service.outputOf(rptId));
    }

    // ---------- 工时（FR-4.5-5-6） ----------

    @PostMapping("/hours")
    @Operation(summary = "工时分项记录", description = "准备/加工/设备；偏离标准 > WORKHOUR_DEVIATION_ALERT 生成主管待办")
    public R<MrpReporting> hours(@RequestBody Map<String, Object> body) {
        return R.ok(service.saveHours((String) body.get("rptId"), dec(body.get("setupHours")),
                dec(body.get("runHours")), dec(body.get("machineHours"))));
    }

    @PostMapping("/hours/confirm/{rptId}")
    @Operation(summary = "工时异常主管确认", description = "PLAN_MGR/ADMIN；确认后放行 submit")
    public R<Void> confirmHours(@PathVariable String rptId) {
        service.confirmHours(rptId);
        return R.ok(null);
    }

    // ---------- 冲销（BR-4.5-25） ----------

    @PostMapping("/reversal")
    @Operation(summary = "冲销申请", description = "原因必填；ReportReversal 审批（PLAN_MGR 签），通过执行五步反向")
    public R<Map<String, Object>> reversal(@RequestBody Map<String, String> body) {
        return R.ok(service.applyReversal(body.get("rptId"), body.get("reason")));
    }

    // ---------- 完工与倒冲（FR-4.5-5-7） ----------

    @PostMapping("/complete/{moId}")
    @Operation(summary = "完工确认接管", description = "校验全工序 DONE+检验无未决+无冻结 → 倒冲补足 → Completed（合格=末道累计）")
    public R<Map<String, Object>> complete(@PathVariable String moId) {
        return R.ok(service.completeMo(moId));
    }

    private static BigDecimal dec(Object v) {
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        return new BigDecimal(String.valueOf(v));
    }
}
