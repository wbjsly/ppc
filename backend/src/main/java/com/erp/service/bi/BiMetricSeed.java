package com.erp.service.bi;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标预注册种子（tasks 2.5）：2.9.1 三 Tab 与 2.9.2 记分卡的全部展示指标
 * 先登记口径并发布（幂等：已存在即跳过）。Formula 即 diff 校验的权威对象。
 */
@Slf4j
@Component
public class BiMetricSeed implements ApplicationRunner {

    private final MetricDictService dictService;

    public BiMetricSeed(MetricDictService dictService) {
        this.dictService = dictService;
    }

    /** 每个指标：key → [名称, 定义, 公式JSON, 时间维度, 来源映射JSON] */
    private static List<Object[]> defs() {
        return List.of(
            // ---- 2.9.1 成本构成（双口径五维） ----
            def("COST_PO_AMT", "PO承诺成本(含税)", "PO 下单行单价×数量按下单期间汇总（含税）", "MONTH",
                "{\"expr\":\"SUM(po_line.qty * po_line.unit_price)\",\"basis\":\"PO\",\"tax\":\"INCLUSIVE\"}",
                "{\"erp_proc_po_line.qty\":\"qty\",\"erp_proc_po_line.unit_price\":\"unit_price\"}"),
            def("COST_INV_AMT", "发票实付成本(含税)", "三方匹配后发票金额按入账期间汇总（含税）", "MONTH",
                "{\"expr\":\"SUM(fin_ap_invoice.amount)\",\"basis\":\"INVOICE\",\"tax\":\"INCLUSIVE\"}",
                "{\"erp_fin_ap_invoice.amount\":\"amount\"}"),
            def("COST_DIFF_AMT", "口径差异", "发票实付 - PO 承诺（价差分析源）", "MONTH",
                "{\"expr\":\"COST_INV_AMT - COST_PO_AMT\"}",
                "{\"derived\":\"COST_INV_AMT,COST_PO_AMT\"}"),
            def("COST_AMT_NO_TAX", "不含税成本", "双口径不含税金额", "MONTH",
                "{\"expr\":\"SUM(base_amt)\",\"tax\":\"EXCLUSIVE\"}",
                "{\"erp_fin_ap_invoice.base_amount\":\"base_amt\"}"),
            def("COST_TAX_AMT", "税额", "进项税额", "MONTH",
                "{\"expr\":\"SUM(tax_amt)\",\"tax\":\"ONLY\"}",
                "{\"erp_fin_ap_invoice.tax_amount\":\"tax_amt\"}"),
            def("COST_FREIGHT", "运费", "运费/杂费单列（不进物料价）", "MONTH",
                "{\"expr\":\"SUM(freight_amt)\"}",
                "{\"erp_proc_po.freight_amt\":\"freight_amt\"}"),
            def("COST_RETURN", "退货冲减", "退货红字当期冲减（正数表示扣减）", "MONTH",
                "{\"expr\":\"SUM(return_amt)\"}",
                "{\"erp_inv_return.red_amount\":\"return_amt\"}"),
            // ---- 2.9.1 价格趋势 ----
            def("PRICE_TREND_AVG", "月度加权采购均价", "月金额/月数量（双口径可切换）", "MONTH",
                "{\"expr\":\"SUM(amount) / NULLIF(SUM(qty),0)\",\"precision\":4}",
                "{\"erp_bi_cost_snapshot\":\"PO_AMT,PO_QTY\"}"),
            def("PRICE_TREND_MOM", "环比", "本月均价 vs 上月均价", "MONTH",
                "{\"expr\":\"(curr - prev) / NULLIF(prev,0) * 100\",\"unit\":\"%\"}",
                "{\"derived\":\"PRICE_TREND_AVG\"}"),
            def("PRICE_TREND_YOY", "同比", "本月均价 vs 去年同月均价", "MONTH",
                "{\"expr\":\"(curr - last_year) / NULLIF(last_year,0) * 100\",\"unit\":\"%\"}",
                "{\"derived\":\"PRICE_TREND_AVG\"}"),
            def("PRICE_TREND_MA3", "3月移动均价", "近 3 个月均价均值", "MONTH",
                "{\"expr\":\"AVG(price_3m)\",\"window\":3}",
                "{\"derived\":\"PRICE_TREND_AVG\"}"),
            // ---- 2.9.1 降本 ----
            def("COST_SAVE_MOM", "环比降本", "(上期月均价-本期月均价)×本期量", "MONTH",
                "{\"expr\":\"(prev_price - curr_price) * qty\"}",
                "{\"derived\":\"PRICE_TREND_AVG\"}"),
            def("COST_SAVE_YOY", "同比降本", "(去年同期均价-本期均价)×本期量", "MONTH",
                "{\"expr\":\"(ly_price - curr_price) * qty\"}",
                "{\"derived\":\"PRICE_TREND_AVG\"}"),
            def("PRICE_COMP_INDEX", "价格竞争力指数", "100×(1−本供应商月均价/同品类全供应商月均价)，design D7",
                "MONTH",
                "{\"expr\":\"GREATEST(0, 100 * (1 - item_avg / NULLIF(category_avg,0)))\",\"range\":\"0-100\"}",
                "{\"derived\":\"PRICE_TREND_AVG\"}"),
            // ---- 2.9.2 记分卡四维 ----
            def("SC_QUALITY_PASS", "来料批次合格率", "当月 IQC 合格批次/总批次×100", "MONTH",
                "{\"expr\":\"passed_lots / NULLIF(total_lots,0) * 100\",\"unit\":\"%\"}",
                "{\"erp_qms_inspection_lot\":\"result,status\"}"),
            def("SC_QUALITY_PPM", "来料 PPM", "当月不良数/来料数×1000000", "MONTH",
                "{\"expr\":\"ng_qty / NULLIF(in_qty,0) * 1000000\",\"unit\":\"PPM\"}",
                "{\"erp_qms_inspection_lot\":\"ng_qty,in_qty\"}"),
            def("SC_DELIVERY_OTD", "准时交付率", "实际到货≤PO承诺交期批次/总批次×100", "MONTH",
                "{\"expr\":\"ontime_lots / NULLIF(total_lots,0) * 100\",\"unit\":\"%\",\"basis\":\"PROMISE_DATE\"}",
                "{\"erp_proc_gr.arrival_date\":\"arrival\",\"erp_proc_po.promise_date\":\"promise\"}"),
            def("SC_DELIVERY_ASN_ACC", "ASN 准确率", "ASN 数量与实收一致笔数/总笔数×100", "MONTH",
                "{\"expr\":\"acc_lots / NULLIF(total_lots,0) * 100\",\"unit\":\"%\"}",
                "{\"erp_scm_asn_line\":\"asn_qty,rec_qty\"}"),
            def("SC_COST_SCORE", "成本维度得分", "价格竞争力指数×指数权重 + 降本得分×降本权重（模型可配，D7 默认 60/40）",
                "MONTH",
                "{\"expr\":\"PRICE_COMP_INDEX * w_index + save_score * (100 - w_index)\",\"w_index\":\"model.COST_INDEX_WEIGHT\"}",
                "{\"derived\":\"PRICE_COMP_INDEX,COST_SAVE_MOM\"}"),
            def("SC_RESPONSE_PO_48H", "PO 48h 确认率", "48h 内确认 PO 数/总 PO 数×100（FR-4.9-7-2）", "MONTH",
                "{\"expr\":\"confirmed_48h / NULLIF(total_po,0) * 100\",\"unit\":\"%\",\"limit_hours\":48}",
                "{\"erp_proc_po.confirm_status\":\"confirm_at,create_date\"}"),
            def("SC_RESPONSE_CPFR", "补货反馈及时率", "补货确认/反馈在时限内完成比例×100", "MONTH",
                "{\"expr\":\"timely_cnt / NULLIF(total_cnt,0) * 100\",\"unit\":\"%\"}",
                "{\"erp_inv_replenish_confirm\":\"confirm_at,create_date\"}"),
            def("SC_TOTAL", "记分卡总分", "四维得分按模型权重加权和（FR-4.9-7-3）", "MONTH",
                "{\"expr\":\"q*W_Q + d*W_D + c*W_C + r*W_R\",\"weights\":\"model.W_QUALITY..W_RESPONSE\"}",
                "{\"derived\":\"SC_QUALITY_PASS,SC_DELIVERY_OTD,SC_COST_SCORE,SC_RESPONSE_PO_48H\"}")
        );
    }

    private static Object[] def(String key, String name, String def, String dim, String formula, String src) {
        return new Object[]{key, name, def, formula, dim, src};
    }

    @Override
    public void run(ApplicationArguments args) {
        int created = 0;
        for (Object[] d : defs()) {
            String key = (String) d[0];
            try {
                if (dictService.current(key) != null) {
                    continue;
                }
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("metricKey", key);
                p.put("name", d[1]);
                p.put("definition", d[2]);
                p.put("formula", d[3]);
                p.put("timeDim", d[4]);
                p.put("srcMapping", d[5]);
                p.put("dataPrecision", 2);
                dictService.register(p);
                dictService.publish(key, (String) d[3]);   // 与登记公式一致 → diff 必过
                created++;
            } catch (Exception e) {
                log.warn("metric seed failed {}: {}", key, e.getMessage());
            }
        }
        if (created > 0) {
            log.info("bi metric dictionary seeded: {} metrics", created);
        }
    }
}
