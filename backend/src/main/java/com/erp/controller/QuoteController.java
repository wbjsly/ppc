package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.sd.SdQuote;
import com.erp.entity.sd.QuoteLine;
import com.erp.entity.sd.QuoteVersion;
import com.erp.service.sd.QuoteService;
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

import java.util.List;
import java.util.Map;

/**
 * 报价域接口（3.2.1 报价创建 / 3.2.2 毛利测算 / 3.2.3 报价转化，spec sales-quote）。
 * 菜单 PERM = ROLE_SALES,ROLE_SALES_MGR,ROLE_ADMIN；审批签署走通用审批待办
 * （负毛利会签节点含 ROLE_FINANCE_MGR，大额链含 ROLE_SALES_DIRECTOR——见 SecurityConfig 白名单）。
 */
@Tag(name = "报价管理")
@RestController
@RequestMapping("/api/sd/quotes")
public class QuoteController {

    private final QuoteService quoteService;

    public QuoteController(QuoteService quoteService) {
        this.quoteService = quoteService;
    }

    @GetMapping
    @Operation(summary = "报价列表", description = "按状态/商机筛选；PUBLISHED 展示有效期")
    public R<Page<SdQuote>> page(@RequestParam(defaultValue = "1") long current,
                               @RequestParam(defaultValue = "10") long size,
                               @RequestParam(required = false) String keyword,
                               @RequestParam(required = false) String status,
                               @RequestParam(required = false) String oppId) {
        return R.ok(quoteService.page(current, size, keyword, status, oppId));
    }

    @GetMapping("/{id}")
    public R<SdQuote> get(@PathVariable String id) {
        return R.ok(quoteService.get(id));
    }

    @GetMapping("/{id}/lines")
    public R<List<QuoteLine>> lines(@PathVariable String id) {
        return R.ok(quoteService.lines(id));
    }

    @GetMapping("/{id}/versions")
    @Operation(summary = "版本与操作留痕", description = "创建/提交/发布/修订/驳回/转化全快照")
    public R<List<QuoteVersion>> versions(@PathVariable String id) {
        return R.ok(quoteService.versions(id));
    }

    /** 预检试算：卡控 errors（L1）/ warnings（L4）+ 取价明细 + 毛利，不落库 */
    @PostMapping("/precheck")
    public R<Map<String, Object>> precheck(@RequestBody Map<String, Object> body) {
        return R.ok(quoteService.precheck(quoteOf(body), linesOf(body)));
    }

    @PostMapping
    @Operation(summary = "创建报价草稿", description = "商机入口；无协议 L1 阻断；MOQ 拒存（样品单豁免）")
    public R<SdQuote> create(@RequestBody Map<String, Object> body) {
        return R.ok(quoteService.create(quoteOf(body), linesOf(body)));
    }

    @PutMapping("/{id}")
    public R<SdQuote> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(quoteService.update(id, quoteOf(body), linesOf(body)));
    }

    @PostMapping("/{id}/margin-confirm")
    @Operation(summary = "低毛利二次确认", description = "0 ≤ 毛利率 < MIN_MARGIN_RATE 时提交前置；留痕确认人与时间")
    public R<SdQuote> marginConfirm(@PathVariable String id) {
        return R.ok(quoteService.marginConfirm(id));
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "提交审批", description = "小额高毛利自动发布；负毛利销售主管+财务会签")
    public R<SdQuote> submit(@PathVariable String id) {
        return R.ok(quoteService.submit(id));
    }

    @PostMapping("/{id}/revise")
    @Operation(summary = "开新版本", description = "旧版本 SUPERSEDED 保留可查、不可转 SO")
    public R<SdQuote> revise(@PathVariable String id) {
        return R.ok(quoteService.revise(id));
    }

    @PostMapping("/{id}/convert-check")
    @Operation(summary = "转化预检", description = "有效期/占用/信用状态；返回可转化与原因")
    public R<Map<String, Object>> convertCheck(@PathVariable String id) {
        return R.ok(quoteService.convertCheck(id));
    }

    @PostMapping("/{id}/convert")
    @Operation(summary = "转化 SO", description = "生成 SO 草稿回写报价；商机自动推进「合同签订」")
    public R<SdQuote> convert(@PathVariable String id) {
        return R.ok(quoteService.convert(id));
    }

    // ---------- 请求体拆解（header 与 lines 平铺传入） ----------

    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    @SuppressWarnings("unchecked")
    private SdQuote quoteOf(Map<String, Object> body) {
        SdQuote q = new SdQuote();
        q.setId((String) body.get("id"));
        q.setOppId((String) body.get("oppId"));
        q.setCustomerId((String) body.get("customerId"));
        q.setOrderType((String) body.get("orderType"));
        q.setContactName((String) body.get("contactName"));
        q.setShipAddress((String) body.get("shipAddress"));
        q.setRemark((String) body.get("remark"));
        return q;
    }

    /** body.lines 是 Jackson 反序列化的 Map 列表，须逐个转换为 QuoteLine（直接强转会 CCE） */
    @SuppressWarnings("unchecked")
    private List<QuoteLine> linesOf(Map<String, Object> body) {
        Object raw = body.get("lines");
        if (!(raw instanceof List)) {
            return List.of();
        }
        List<QuoteLine> out = new java.util.ArrayList<>();
        for (Object o : (List<Object>) raw) {
            if (o instanceof QuoteLine) {
                out.add((QuoteLine) o);
            } else if (o instanceof Map) {
                out.add(mapper.convertValue(o, QuoteLine.class));
            }
        }
        return out;
    }
}
