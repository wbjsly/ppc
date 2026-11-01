package com.erp.service.impl.ops;

import com.erp.entity.ops.OpsCapacityMetric;
import com.erp.service.ops.OpsAlertService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 容量采集与预警实现（add-ops-sla，BR-5.8-10）。
 * 程序内自采三指标（偏差声明：CPU 为进程级口径）：
 * DB_SIZE=information_schema 数据+索引体积 / CONN_POOL=HikariCP 活跃占比 /
 * CPU=OperatingSystemMXBean 进程 CPU 采样均值。
 * 连续 3 天 > CAPACITY_UTIL_THRESHOLD → 容量工单（经告警 P1 通道）+ 外推红线日期。
 */
@Slf4j
@Service
public class OpsCapacityServiceImpl implements com.erp.service.ops.OpsCapacityService {

    private final JdbcTemplate jdbc;
    private final OpsAlertService alertService;

    @Value("${ops.capacity.util-threshold:80}")
    private int utilThreshold;

    public OpsCapacityServiceImpl(JdbcTemplate jdbc, OpsAlertService alertService) {
        this.jdbc = jdbc;
        this.alertService = alertService;
    }

    @Override
    public int collect() {
        LocalDate today = LocalDate.now();
        int n = 0;
        // ① DB 存储：数据+索引总体积（MB）
        try {
            Map<String, Object> m = jdbc.queryForMap(
                    "SELECT COALESCE(SUM(DATA_LENGTH + INDEX_LENGTH), 0) / 1024 / 1024 AS mb, "
                            + "COALESCE(SUM(DATA_LENGTH + INDEX_LENGTH), 0) / 1024 / 1024 AS max_mb "
                            + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()");
            upsert(today, OpsCapacityMetric.K_DB_SIZE,
                    dec(m.get("mb")), dec(m.get("max_mb")), "MB");
            n++;
        } catch (Exception e) {
            log.warn("capacity DB_SIZE collect failed: {}", e.getMessage());
        }
        // ② 连接池：HikariCP 活跃/最大（JMX）
        try {
            ObjectName pool = new ObjectName("com.zaxxer.hikari:type=Pool *");
            var beans = ManagementFactory.getPlatformMBeanServer().queryMBeans(pool, null);
            long active = 0;
            long max = 0;
            for (javax.management.ObjectInstance oi : beans) {
                ObjectName on = oi.getObjectName();
                Object a = ManagementFactory.getPlatformMBeanServer().getAttribute(on, "ActiveConnections");
                Object mx = ManagementFactory.getPlatformMBeanServer().getAttribute(on, "MaximumPoolSize");
                if (a instanceof Number an) {
                    active += an.longValue();
                }
                if (mx instanceof Number mn) {
                    max += mn.longValue();
                }
            }
            if (max > 0) {
                BigDecimal pct = BigDecimal.valueOf(active * 100)
                        .divide(BigDecimal.valueOf(max), 2, RoundingMode.HALF_UP);
                upsert(today, OpsCapacityMetric.K_CONN_POOL, pct, BigDecimal.valueOf(max), "%");
                n++;
            }
        } catch (Exception e) {
            log.warn("capacity CONN_POOL collect failed: {}", e.getMessage());
        }
        // ③ 进程 CPU（采样均值，两次读取间隔 200ms）
        try {
            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double u1 = os.getProcessCpuLoad();
            Thread.sleep(200);
            double u2 = os.getProcessCpuLoad();
            double v = (u1 + u2) / 2;
            if (v >= 0) {
                upsert(today, OpsCapacityMetric.K_CPU,
                        BigDecimal.valueOf(v * 100).setScale(2, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(100), "%");
                n++;
            }
        } catch (Exception e) {
            log.warn("capacity CPU collect failed: {}", e.getMessage());
        }
        checkAndAlert(today);
        return n;
    }

    private void upsert(LocalDate d, String key, BigDecimal value, BigDecimal max, String unit) {
        if (value == null) {
            return;
        }
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_ops_capacity_metric WHERE METRIC_DATE=? AND METRIC_KEY=?",
                Integer.class, d, key);
        if (cnt != null && cnt > 0) {
            jdbc.update("UPDATE erp_ops_capacity_metric SET METRIC_VALUE=?, MAX_VALUE=?, UNIT=? "
                            + "WHERE METRIC_DATE=? AND METRIC_KEY=?",
                    value, max, unit, d, key);
        } else {
            jdbc.update("INSERT INTO erp_ops_capacity_metric (ID, METRIC_DATE, METRIC_KEY, "
                            + "METRIC_VALUE, MAX_VALUE, UNIT, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (UUID(), ?, ?, ?, ?, ?, 'capacity-collector', NOW(), '0', 0)",
                    d, key, value, max, unit);
        }
    }

    /** 连续 3 天 > 阈值 → 告警（容量工单）+ 外推红线日期（BR-5.8-10） */
    private void checkAndAlert(LocalDate today) {
        for (String key : new String[]{OpsCapacityMetric.K_DB_SIZE, OpsCapacityMetric.K_CONN_POOL,
                OpsCapacityMetric.K_CPU}) {
            List<Map<String, Object>> recent = jdbc.queryForList(
                    "SELECT METRIC_VALUE FROM erp_ops_capacity_metric "
                            + "WHERE METRIC_KEY=? AND DEL_FLAG='0' AND METRIC_DATE >= ? "
                            + "ORDER BY METRIC_DATE DESC LIMIT 3", key, today.minusDays(2));
            if (recent.size() < 3) {
                continue;
            }
            boolean over = recent.stream().allMatch(r ->
                    dec(r.get("METRIC_VALUE")) != null
                            && dec(r.get("METRIC_VALUE")).compareTo(BigDecimal.valueOf(utilThreshold)) > 0);
            if (!over) {
                continue;
            }
            String projection = extrapolateRedline(key);
            BigDecimal latest = dec(recent.get(0).get("METRIC_VALUE"));
            // P1 → 告警服务落库+推送（容量工单经告警通道，联动升级引擎）
            alertService.raise("P1", "capacity",
                    "容量预警：" + key + " 连续 3 天超 " + utilThreshold + "%",
                    "最新值 " + latest + "%，近 3 天均超阈；外推预测：" + projection,
                    latest, BigDecimal.valueOf(utilThreshold), key);
        }
    }

    /** 近 14 天线性外推到达 90% 红线的预测日期 */
    private String extrapolateRedline(String key) {
        List<Map<String, Object>> pts = jdbc.queryForList(
                "SELECT METRIC_DATE AS d, METRIC_VALUE AS v FROM erp_ops_capacity_metric "
                        + "WHERE METRIC_KEY=? AND DEL_FLAG='0' ORDER BY METRIC_DATE DESC LIMIT 14", key);
        if (pts.size() < 3) {
            return "数据不足，无法外推";
        }
        // 简单线性回归 y = a + b*x（x=距今天数）
        int n = pts.size();
        double sx = 0, sy = 0, sxy = 0, sxx = 0;
        for (int i = 0; i < n; i++) {
            double x = i; // 倒序最新在前
            BigDecimal v = dec(pts.get(i).get("v"));
            double y = v == null ? 0 : v.doubleValue();
            sx += x; sy += y; sxy += x * y; sxx += x * x;
        }
        double b = (n * sxy - sx * sy) / (n * sxx - sx * sx);
        double a = (sy - b * sx) / n;
        if (b >= -1e-9) {
            return "趋势平稳或上升，90% 红线需立即行动（当前斜率 " + String.format("%.3f", b) + "%/天）";
        }
        double daysTo90 = (90 - a) / b; // b 为负，天数为正
        if (daysTo90 > 365) {
            return "外推 365 天内不触及 90% 红线";
        }
        LocalDate eta = LocalDate.now().minusDays(0).plusDays((long) Math.ceil(daysTo90));
        return "外推约 " + (long) Math.ceil(daysTo90) + " 天（" + eta + "）触及 90% 红线";
    }

    @Override
    public List<Map<String, Object>> metrics(int days) {
        int d = Math.min(Math.max(days, 1), 90);
        return jdbc.queryForList(
                "SELECT METRIC_DATE AS metricDate, METRIC_KEY AS metricKey, "
                        + "METRIC_VALUE AS metricValue, MAX_VALUE AS maxVal, UNIT AS unit "
                        + "FROM erp_ops_capacity_metric WHERE DEL_FLAG='0' "
                        + "AND METRIC_DATE >= DATE_SUB(CURDATE(), INTERVAL ? DAY) "
                        + "ORDER BY METRIC_DATE DESC, METRIC_KEY", d);
    }

    private BigDecimal dec(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }
}
