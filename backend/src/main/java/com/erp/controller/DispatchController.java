package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.mrp.MrpDispatch;
import com.erp.service.mrp.DispatchService;
import com.erp.service.mrp.DispatchService.GenReq;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
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
 * 派工单接口（change add-dispatch-management，spec dispatch-management，菜单 5.6.1）。
 * 权限：读/写 = PLANNER/PLAN_MGR/SHOP_FLOOR/ADMIN；确认 = SHOP_FLOOR/PLAN_MGR/ADMIN（service 层校验）。
 */
@Tag(name = "派工管理")
@RestController
@RequestMapping("/api/mrp/dispatch")
public class DispatchController {

    private final DispatchService service;

    public DispatchController(DispatchService service) {
        this.service = service;
    }

    @GetMapping("/pending")
    @Operation(summary = "待派工列表", description = "Released 工单 × 未派工序；priority+planStartDate 排序（D6）")
    public R<List<Map<String, Object>>> pending(@RequestParam(required = false) String keyword) {
        return R.ok(service.pending(keyword));
    }

    @GetMapping("/list")
    @Operation(summary = "派工单列表", description = "确认/取消/重排的操作对象（生成后刷新可找回）；状态+关键字筛选")
    public R<List<MrpDispatch>> list(@RequestParam(required = false) String status,
                                     @RequestParam(required = false) String keyword) {
        return R.ok(service.list(status, keyword));
    }

    @PostMapping("/generate")
    @Operation(summary = "生成派工单（单/批量）", description = "齐套 L1 门禁 + WC 校验 + 前向排程；422 消息含齐套率与缺料清单")
    public R<List<MrpDispatch>> generate(@RequestBody Map<String, Object> body) {
        return R.ok(service.generate(parseRows(body)));
    }

    @PostMapping("/confirm/{id}")
    @Operation(summary = "确认派工单", description = "SHOP_FLOOR/PLAN_MGR/ADMIN；双点齐套校验 + 触发预扣")
    public R<MrpDispatch> confirm(@PathVariable String id) {
        return R.ok(service.confirm(id));
    }

    @PutMapping("/cancel/{id}")
    @Operation(summary = "取消派工单", description = "原因必填；工序回 PENDING；MO 无活动派工时释放预扣")
    public R<MrpDispatch> cancel(@PathVariable String id, @RequestBody Map<String, String> body) {
        return R.ok(service.cancel(id, body.get("reason")));
    }

    @PostMapping("/reschedule")
    @Operation(summary = "重新排程", description = "已确认占用固定；未确认按优先级重装箱（低优先级后移，留痕）")
    public R<List<MrpDispatch>> reschedule(@RequestBody Map<String, Object> body) {
        String wcCode = body.get("wcCode") == null ? null : String.valueOf(body.get("wcCode"));
        Object from = body.get("from");
        LocalDate d = from == null ? null : LocalDate.parse(String.valueOf(from));
        return R.ok(service.reschedule(wcCode, d));
    }

    @PostMapping("/shortage-apply")
    @Operation(summary = "缺料派工申请", description = "C-4.5-12 L2：挂审批底座单节点（计划主管签）；同一工序 PENDING 拒重")
    public R<Map<String, Object>> applyShortage(@RequestBody Map<String, Object> body) {
        Integer opSeq = body.get("opSeq") == null ? null
                : Integer.valueOf(String.valueOf(body.get("opSeq")));
        return R.ok(service.applyShortage(String.valueOf(body.get("moId")), opSeq));
    }

    @PostMapping("/outsource-apply")
    @Operation(summary = "外协改派申请", description = "BR-4.5-21 L2：PLANNER/ADMIN 提交、计划主管签；目标须外协 WC（C-4.5-05）")
    public R<Map<String, Object>> applyOutsource(@RequestBody Map<String, String> body) {
        return R.ok(service.applyOutsource(body.get("dispatchId"), body.get("targetWcCode")));
    }

    @GetMapping("/outsource-candidates")
    @Operation(summary = "外协候选推荐", description = "定额矩阵筛外协 WC（C-4.5-05 前置过滤）+ 今日负荷 + 协议价参考（D8 仅展示）；dispatchId 或 opCode 二选一")
    public R<List<Map<String, Object>>> outsourceCandidates(
            @RequestParam(required = false) String opCode,
            @RequestParam(required = false) String productCode,
            @RequestParam(required = false) String dispatchId) {
        return R.ok(service.outsourceCandidates(opCode, productCode, dispatchId));
    }

    @SuppressWarnings("unchecked")
    private List<GenReq> parseRows(Map<String, Object> body) {
        Object raw = body.get("rows");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new com.erp.common.ServiceException(422, "rows 必填");
        }
        return list.stream().map(o -> {
            Map<String, Object> m = (Map<String, Object>) o;
            Object opSeq = m.get("opSeq");
            return new GenReq(String.valueOf(m.get("moId")),
                    opSeq == null ? null : Integer.valueOf(String.valueOf(opSeq)),
                    m.get("wcCode") == null ? null : String.valueOf(m.get("wcCode")));
        }).toList();
    }
}
