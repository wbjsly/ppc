package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.fin.InputInvoiceDao;
import com.erp.dao.fin.RedFlushApplyDao;
import com.erp.dao.fin.TaxReturnAdjDao;
import com.erp.dao.fin.TaxReturnDao;
import com.erp.entity.fin.InputInvoice;
import com.erp.entity.fin.RedFlushApply;
import com.erp.entity.fin.TaxReturn;
import com.erp.entity.fin.TaxReturnAdj;
import com.erp.service.fin.ExportTaxRefundService;
import com.erp.service.fin.RiskDashboardService;
import com.erp.service.fin.TaxBurdenService;
import com.erp.service.fin.TaxPreferenceService;
import com.erp.service.fin.TaxRiskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 风险汇聚看板实现（design D5：单源失败降级，不使看板整体失败）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskDashboardServiceImpl implements RiskDashboardService {

    private final TaxRiskService taxRiskService;
    private final TaxPreferenceService prefService;
    private final TaxBurdenService burdenService;
    private final ExportTaxRefundService exportService;
    private final InputInvoiceDao inputDao;
    private final TaxReturnDao returnDao;
    private final TaxReturnAdjDao adjDao;
    private final RedFlushApplyDao flushDao;

    @Override
    public Map<String, Object> dashboard(String periodEnd) {
        final String period = (periodEnd == null || periodEnd.isBlank())
                ? LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"))
                : periodEnd;
        List<Map<String, Object>> signals = new ArrayList<>();
        Map<String, Object> sources = new LinkedHashMap<>();

        // ---- 本叶 3 类 ----
        collect("tax-cert", sources, signals, () -> {
            Map<String, Object> w = taxRiskService.certWarnings();
            addSignals(signals, "tax-cert", "L3", "税控/证书到期",
                    (List<Map<String, Object>>) w.get("expiring"), "更新证书或改用数电票通道（C-4.14-14）");
            addSignals(signals, "tax-cert", "L2", "税控/证书不可用",
                    (List<Map<String, Object>>) w.get("unusable"), "通道已阻断，更新后补开");
        });
        collect("tax-preference", sources, signals, () -> {
            Map<String, Object> w = prefService.warnings();
            addSignals(signals, "tax-preference", "L3", "优惠备案临期",
                    (List<Map<String, Object>>) w.get("expiring"), "启动续期（BR-4.14-06）");
            addSignals(signals, "tax-preference", "L4", "优惠备案冻结/终止",
                    (List<Map<String, Object>>) w.get("frozenOrTerminated"), "评估补税风险或补齐条件");
        });
        collect("tax-burden", sources, signals, () -> {
            List<Map<String, Object>> s = burdenService.signals(period, 6);
            for (Map<String, Object> sig : s) {
                sig.put("type", "TAX_BURDEN_BREACH");
                sig.put("object", "所属期 " + sig.get("period"));
                sig.put("occurredAt", sig.get("period"));
                signals.add(sig);
            }
        });

        // ---- 跨叶只读 5 类（不重建规则，只读拉取） ----
        collect("input-cert-expiry", sources, signals, () -> {
            // M9-2 认证临期：距开票日剩余 ≤ 预警阈值的未认证发票（同黄标口径）
            LocalDate today = LocalDate.now();
            List<InputInvoice> pendings = inputDao.selectList(new LambdaQueryWrapper<InputInvoice>()
                    .eq(InputInvoice::getDelFlag, "0")
                    .in(InputInvoice::getCertStatus, "PENDING_INSPECT", "MATCHED", "DIFF", "REGISTERED")
                    .isNotNull(InputInvoice::getInvoiceDate));
            for (InputInvoice inv : pendings) {
                long days = inv.getInvoiceDate() == null ? Long.MAX_VALUE
                        : ChronoUnit.DAYS.between(inv.getInvoiceDate(), today);
                long left = 360 - days;
                if (left >= 0 && left <= 30) {
                    signals.add(signal("input-cert-expiry", "L3", "进项认证临期",
                            inv.getInvoiceNo(), "剩余 " + left + " 天超认证期限",
                            "尽快认证抵扣（C-4.14-07）"));
                }
            }
        });
        collect("export-refund", sources, signals, () -> {
            Map<String, Object> w = exportService.warnings();
            addSignals(signals, "export-refund", "L3", "出口退税单证预警",
                    (List<Map<String, Object>>) w.get("level1"), "60 天内补齐单证");
            addSignals(signals, "export-refund", "L2", "出口退税单证紧急",
                    (List<Map<String, Object>>) w.get("level2"), "30 天内补齐单证");
            addSignals(signals, "export-refund", "L2", "出口退税单证超期",
                    (List<Map<String, Object>>) w.get("overdue"), "评估视同内销补税");
        });
        collect("overdue-return", sources, signals, () -> {
            List<TaxReturn> overdue = returnDao.selectList(new LambdaQueryWrapper<TaxReturn>()
                    .eq(TaxReturn::getDelFlag, "0")
                    .eq(TaxReturn::getOverdueFlag, "1")
                    .eq(TaxReturn::getStatus, TaxReturn.ST_OVERDUE));
            for (TaxReturn r : overdue) {
                signals.add(signal("overdue-return", "L2", "逾期申报事件单",
                        r.getReturnNo(), "所属期 " + r.getPeriod() + " 逾期申报",
                        "跟进滞纳金与更正申报"));
            }
        });
        collect("pending-adjust", sources, signals, () -> {
            List<TaxReturnAdj> adj = adjDao.selectList(new LambdaQueryWrapper<TaxReturnAdj>()
                    .eq(TaxReturnAdj::getDelFlag, "0")
                    .eq(TaxReturnAdj::getStatus, TaxReturnAdj.ST_PENDING_APPROVAL));
            for (TaxReturnAdj a : adj) {
                signals.add(signal("pending-adjust", "L4", "跨期调整待办",
                        a.getAdjNo(), "所属期 " + a.getPeriod() + " 调整待审批",
                        "跟进审批（C-4.14-05）"));
            }
        });
        collect("frequent-red-flush", sources, signals, () -> {
            // M9-4 频繁红冲标记：同客户窗口内超次数阈值升级的红冲申请（FR-4.14-4-2）
            List<RedFlushApply> flagged = flushDao.selectList(new LambdaQueryWrapper<RedFlushApply>()
                    .eq(RedFlushApply::getDelFlag, "0")
                    .eq(RedFlushApply::getFreqFlag, "1"));
            for (RedFlushApply a : flagged) {
                signals.add(signal("frequent-red-flush", "L4", "频繁红冲标记",
                        a.getApplyNo(), "触发频繁红冲升级阈值", "复核业务真实性"));
            }
        });

        // 分级排序（L2 紧急在前）
        signals.sort((a, b) -> String.valueOf(a.get("level"))
                .compareTo(String.valueOf(b.get("level"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periodEnd", period);
        out.put("signals", signals);
        out.put("count", signals.size());
        out.put("sources", sources);
        return out;
    }

    // ================================================================
    // 内部
    // ================================================================

    private void collect(String source, Map<String, Object> sources,
                         List<Map<String, Object>> signals, Runnable body) {
        try {
            body.run();
            sources.put(source, "OK");
        } catch (Exception e) {
            log.warn("risk dashboard source {} unavailable: {}", source, e.getMessage());
            sources.put(source, "暂不可用");
        }
    }

    private void addSignals(List<Map<String, Object>> signals, String source, String level,
                            String type, List<Map<String, Object>> rows, String action) {
        if (rows == null) {
            return;
        }
        for (Map<String, Object> row : rows) {
            Object prefOrCert = row.get("preference") != null ? row.get("preference") : row.get("cert");
            String object = prefOrCert != null ? String.valueOf(prefOrCert) : String.valueOf(row);
            signals.add(signal(source, level, type, object,
                    String.valueOf(row.getOrDefault("reason",
                            row.getOrDefault("daysToEnd", ""))), action));
        }
    }

    private Map<String, Object> signal(String source, String level, String type,
                                       String object, String detail, String action) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("source", source);
        s.put("level", level);
        s.put("type", type);
        s.put("object", object);
        s.put("detail", detail);
        s.put("occurredAt", String.valueOf(LocalDate.now()));
        s.put("suggestedAction", action);
        return s;
    }
}
