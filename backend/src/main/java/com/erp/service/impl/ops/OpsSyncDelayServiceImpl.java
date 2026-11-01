package com.erp.service.impl.ops;

import com.erp.service.ops.OpsAlertService;
import com.erp.service.ops.OpsSyncDelayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 同步延迟采集实现（add-ops-sla，BR-5.8-11）。
 * 口径：erp_ops_outbox.OCCURRED_AT → erp_intf_delivery.LAST_AT（最近一次尝试）时差，
 * 按三类事件前缀（SO_/STOCK./FIN）聚合近 24h 已 ACK 投递。
 */
@Slf4j
@Service
public class OpsSyncDelayServiceImpl implements OpsSyncDelayService {

    private final JdbcTemplate jdbc;
    private final OpsAlertService alertService;

    @Value("${ops.sync-delay.threshold-min:5}")
    private int thresholdMin;

    public OpsSyncDelayServiceImpl(JdbcTemplate jdbc, OpsAlertService alertService) {
        this.jdbc = jdbc;
        this.alertService = alertService;
    }

    /** 三类事件前缀映射（订单/库存/对账） */
    private static final String[][] CATEGORIES = {
            {"ORDER", "SO_"},
            {"STOCK", "STOCK."},
            {"RECON", "FIN."}
    };

    @Override
    public int collect() {
        int rows = 0;
        BigDecimal threshold = BigDecimal.valueOf(thresholdMin);
        for (String[] cat : CATEGORIES) {
            String name = cat[0];
            String prefix = cat[1];
            try {
                Map<String, Object> m = jdbc.queryForMap(
                        "SELECT COUNT(*) AS cnt, "
                                + "COALESCE(AVG(TIMESTAMPDIFF(SECOND, o.OCCURRED_AT, d.LAST_AT)),0) AS avg_s, "
                                + "COALESCE(MAX(TIMESTAMPDIFF(SECOND, o.OCCURRED_AT, d.LAST_AT)),0) AS max_s "
                                + "FROM erp_ops_outbox o "
                                + "JOIN erp_intf_delivery d ON d.EVENT_ID=o.EVENT_ID AND d.STATUS='ACKED' "
                                + "AND d.DEL_FLAG='0' "
                                + "WHERE o.EVENT_TYPE LIKE ? AND o.DEL_FLAG='0' "
                                + "AND o.OCCURRED_AT >= DATE_SUB(NOW(), INTERVAL 24 HOUR)",
                        prefix + "%");
                long cnt = num(m.get("cnt"));
                if (cnt == 0) {
                    continue;
                }
                BigDecimal avgMin = BigDecimal.valueOf(num(m.get("avg_s")))
                        .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
                rows++;
                // 超阈 → 三级告警（连续 3 次 Critical 由升级引擎按 OPEN 未认领处理）
                if (avgMin.compareTo(threshold) > 0) {
                    String level = avgMin.compareTo(threshold.multiply(BigDecimal.valueOf(3))) > 0
                            ? "P1" : (avgMin.compareTo(threshold.multiply(BigDecimal.valueOf(2))) > 0 ? "P2" : "P3");
                    alertService.raise(level, "sync-delay",
                            "同步延迟超阈：" + name + " 类事件", 
                            "近 24h 平均延迟 " + avgMin + " 分钟（阈值 " + thresholdMin + " 分钟，"
                                    + cnt + " 条已投递）",
                            avgMin, threshold, name);
                }
            } catch (Exception e) {
                log.warn("sync delay collect failed for {}: {}", name, e.getMessage());
            }
        }
        return rows;
    }

    @Override
    public List<Map<String, Object>> trend(int days) {
        int d = Math.min(Math.max(days, 1), 90);
        return jdbc.queryForList(
                "SELECT DATE(o.OCCURRED_AT) AS metricDate, "
                        + "CASE WHEN o.EVENT_TYPE LIKE 'SO_%' THEN 'ORDER' "
                        + "     WHEN o.EVENT_TYPE LIKE 'STOCK.%' THEN 'STOCK' "
                        + "     ELSE 'RECON' END AS category, "
                        + "ROUND(AVG(TIMESTAMPDIFF(SECOND, o.OCCURRED_AT, d.LAST_AT))/60, 2) AS avgDelayMin, "
                        + "COUNT(*) AS cnt "
                        + "FROM erp_ops_outbox o "
                        + "JOIN erp_intf_delivery d ON d.EVENT_ID=o.EVENT_ID AND d.STATUS='ACKED' AND d.DEL_FLAG='0' "
                        + "WHERE o.DEL_FLAG='0' AND o.OCCURRED_AT >= DATE_SUB(CURDATE(), INTERVAL ? DAY) "
                        + "GROUP BY DATE(o.OCCURRED_AT), category ORDER BY metricDate DESC", d);
    }

    private long num(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }
}
