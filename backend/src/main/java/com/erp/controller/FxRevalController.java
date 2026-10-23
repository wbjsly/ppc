package com.erp.controller;

import com.erp.common.R;
import com.erp.dao.fin.FinBalanceDao;
import com.erp.service.fin.FxRevalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外币期末重估（M8-5-2/8.5-3，add-fx-revaluation，spec fx-revaluation）。
 * 试算/生成/批次列表/明细下钻/汇兑损益看板（6604 余额=利润表口径）。
 */
@Tag(name = "外币期末重估")
@RestController
@RequestMapping("/api/fin")
public class FxRevalController {

    /** 汇兑损益科目（decision③：6603 被租赁利息占用，新建 6604） */
    private static final String ACCT_FX = "6604";

    private final FxRevalService revalService;
    private final FinBalanceDao balanceDao;

    public FxRevalController(FxRevalService revalService, FinBalanceDao balanceDao) {
        this.revalService = revalService;
        this.balanceDao = balanceDao;
    }

    @Operation(summary = "试算（不落库）：三类外币货币性项目逐笔差额 + 币种×类型汇总")
    @PostMapping("/fx-reval/trial-run")
    public R<Map<String, Object>> trialRun(@RequestParam String period) {
        return R.ok(revalService.trialRun(period));
    }

    @Operation(summary = "生成重估批次（上期自动冲回 + 台账 + DRAFT 凭证；同期重复 422）")
    @PostMapping("/fx-reval/generate")
    public R<Map<String, Object>> generate(@RequestParam String period) {
        return R.ok(revalService.generate(period));
    }

    @Operation(summary = "批次列表（期间倒序：重估额/冲回额/净额/凭证状态）")
    @GetMapping("/fx-reval/batches")
    public R<List<Map<String, Object>>> batches() {
        return R.ok(revalService.batches());
    }

    @Operation(summary = "批次明细下钻（币种×类型汇总 + 逐笔 + 凭证链）")
    @GetMapping("/fx-reval/batches/{period}/details")
    public R<Map<String, Object>> batchDetails(@PathVariable String period) {
        return R.ok(revalService.batchDetails(period));
    }

    @Operation(summary = "汇兑损益看板（6604 科目累计发生额=利润表口径）")
    @GetMapping("/fx-gain-loss/summary")
    public R<Map<String, Object>> gainLossSummary(
            @RequestParam(required = false) String period) {
        String p = period == null || period.isBlank()
                ? java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"))
                : period;
        BigDecimal cumDr = nvl(balanceDao.cumulativeBalance(ACCT_FX, p));
        Map<String, Object> row = balanceDao.queryBalances(p, ACCT_FX, null).stream()
                .findFirst().orElse(null);
        BigDecimal dr = row == null ? BigDecimal.ZERO : nvl((BigDecimal) row.get("DR_TOTAL"));
        BigDecimal cr = row == null ? BigDecimal.ZERO : nvl((BigDecimal) row.get("CR_TOTAL"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", p);
        out.put("accountCode", ACCT_FX);
        out.put("periodDebit", dr);   // 本期汇兑损失（借方发生）
        out.put("periodCredit", cr);  // 本期汇兑收益（贷方发生）
        out.put("periodNet", cr.subtract(dr)); // 正=净收益 负=净损失
        out.put("cumBalance", cumDr); // 累计（利润表口径参考）
        return R.ok(out);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
