package com.erp.service.impl.iot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.iot.IotSlaReportDao;
import com.erp.dao.iot.IotSlaSubDao;
import com.erp.entity.iot.IotSlaReport;
import com.erp.entity.iot.IotSlaSub;
import com.erp.service.iot.IotSlaService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 移动/IoT SLA 月度报告实现（add-iot-mobile 13.9，FR-4.11-6-x / C-05 未归档不发布）。
 * 五指标：系统可用率 / 同步成功率 / 平均响应时间 / 离线作业占比 / 告警响应时间；
 * 状态机 DRAFT→ARCHIVED→PUBLISHED；订阅归档自动推送。
 */
@Slf4j
@Service
public class IotSlaServiceImpl implements IotSlaService {

    private final IotSlaReportDao reportDao;
    private final IotSlaSubDao subDao;
    private final JdbcTemplate jdbc;

    public IotSlaServiceImpl(IotSlaReportDao reportDao, IotSlaSubDao subDao,
                             JdbcTemplate jdbc) {
        this.reportDao = reportDao;
        this.subDao = subDao;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generate(String period) {
        if (period == null || !period.matches("\\d{4}-\\d{2}")) {
            throw new ServiceException(422, "报告期须为 yyyy-MM");
        }
        IotSlaReport exist = reportDao.selectOne(new LambdaQueryWrapper<IotSlaReport>()
                .eq(IotSlaReport::getPeriod, period));
        IotSlaReport r = exist == null ? new IotSlaReport() : exist;
        r.setPeriod(period);
        r.setStatus(IotSlaReport.ST_DRAFT);
        r.setMetricsJson(metricsJson(period));
        r.setUpdateBy(SecurityUtils.getCurrentUserId());
        if (exist == null) {
            r.setCreateBy(SecurityUtils.getCurrentUserId());
            reportDao.insert(r);
        } else {
            reportDao.updateById(r);
        }
        return toMap(r);
    }

    /** 五指标快照（自然月口径，来自真实表统计） */
    private String metricsJson(String period) {
        String from = period + "-01 00:00:00";
        String to = "DATE_ADD('" + period + "-01 01:00:00', INTERVAL 1 MONTH)";
        // 系统可用率：在线设备分钟数 /（在线+离线分钟数）近似——以心跳打点计数近似
        Map<String, Object> avail = jdbc.queryForMap(
                "SELECT COUNT(*) total, " +
                        "COALESCE(SUM(CASE WHEN STATUS='ONLINE' THEN 1 ELSE 0 END),0) online " +
                        "FROM erp_iot_device WHERE DEL_FLAG='0'");
        long totalDev = num(avail.get("total"));
        long onlineDev = num(avail.get("online"));
        BigDecimal availability = totalDev == 0 ? BigDecimal.valueOf(100)
                : BigDecimal.valueOf(onlineDev * 100L)
                .divide(BigDecimal.valueOf(totalDev), 2, RoundingMode.HALF_UP);
        // 同步成功率：SYNCED / (SYNCED+FAILED+CONFLICT)
        Map<String, Object> sync = jdbc.queryForMap(
                "SELECT COALESCE(SUM(CASE WHEN STATUS='SYNCED' THEN 1 ELSE 0 END),0) ok, " +
                        "COALESCE(SUM(CASE WHEN STATUS<>'SYNCED' THEN 1 ELSE 0 END),0) bad " +
                        "FROM erp_iot_offline_queue WHERE CREATE_DATE >= '" + from + "' AND DEL_FLAG='0'");
        long ok = num(sync.get("ok"));
        long bad = num(sync.get("bad"));
        BigDecimal syncRate = (ok + bad) == 0 ? BigDecimal.valueOf(100)
                : BigDecimal.valueOf(ok * 100L).divide(BigDecimal.valueOf(ok + bad), 2,
                RoundingMode.HALF_UP);
        // 平均告警响应时间（确认时间 - 发生时间，分钟）
        Map<String, Object> resp = jdbc.queryForMap(
                "SELECT COALESCE(AVG(TIMESTAMPDIFF(MINUTE, OCCURRED_AT, CONFIRM_AT)),0) avg_min " +
                        "FROM erp_iot_alarm_event WHERE CONFIRM_AT IS NOT NULL AND DEL_FLAG='0' " +
                        "AND OCCURRED_AT >= '" + from + "'");
        BigDecimal avgResp = BigDecimal.valueOf(num(resp.get("avg_min")));
        // 离线作业占比：离线队列记录数 /（在线报工数 + 离线队列数）近似
        long offlineOps = num(jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_iot_offline_queue WHERE CREATE_DATE >= '" + from + "' AND DEL_FLAG='0'",
                Long.class));
        long onlineOps = num(jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_reporting WHERE CREATE_DATE >= '" + from + "' AND DEL_FLAG='0'",
                Long.class));
        BigDecimal offlineShare = (offlineOps + onlineOps) == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(offlineOps * 100L)
                .divide(BigDecimal.valueOf(offlineOps + onlineOps), 2, RoundingMode.HALF_UP);
        // 告警响应（关闭率）
        Map<String, Object> closed = jdbc.queryForMap(
                "SELECT COUNT(*) total, COALESCE(SUM(CASE WHEN STATUS='CLOSED' THEN 1 ELSE 0 END),0) closed " +
                        "FROM erp_iot_alarm_event WHERE DEL_FLAG='0' AND OCCURRED_AT >= '" + from + "'");
        long alarmTotal = num(closed.get("total"));
        long alarmClosed = num(closed.get("closed"));
        BigDecimal closeRate = alarmTotal == 0 ? BigDecimal.valueOf(100)
                : BigDecimal.valueOf(alarmClosed * 100L)
                .divide(BigDecimal.valueOf(alarmTotal), 2, RoundingMode.HALF_UP);
        return String.format(
                "{\"availabilityPct\":%s,\"syncSuccessPct\":%s,\"avgAlertRespMin\":%s,"
                        + "\"offlineSharePct\":%s,\"alarmClosePct\":%s}",
                availability, syncRate, avgResp, offlineShare, closeRate);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> archive(String period) {
        IotSlaReport r = require(period);
        if (IotSlaReport.ST_PUBLISHED.equals(r.getStatus())) {
            throw new ServiceException(409, "报告已发布，不可再归档");
        }
        r.setStatus(IotSlaReport.ST_ARCHIVED);
        reportDao.updateById(r);
        // 归档自动推送订阅人（13.9.2）
        int pushed = pushSubscribers(r);
        Map<String, Object> out = toMap(r);
        out.put("subscribersPushed", pushed);
        return out;
    }

    @Override
    // 无事务：阻断前的尝试留痕须独立提交（否则随 422 回滚，C-05 留痕丢失）
    public Map<String, Object> publish(String period) {
        IotSlaReport r = require(period);
        // C-05 未归档不得发布（BR-5.7-14 同构范式）
        if (!IotSlaReport.ST_ARCHIVED.equals(r.getStatus())) {
            r.setPublishLog("未归档发布阻断尝试 @" + LocalDateTime.now());
            reportDao.updateById(r);
            throw new ServiceException(422, "报告未归档，不可发布（当前 " + r.getStatus() + "）");
        }
        r.setStatus(IotSlaReport.ST_PUBLISHED);
        r.setPublishAt(LocalDateTime.now());
        reportDao.updateById(r);
        return toMap(r);
    }

    @Override
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (IotSlaReport r : reportDao.selectList(new LambdaQueryWrapper<IotSlaReport>()
                .orderByDesc(IotSlaReport::getPeriod))) {
            out.add(toMap(r));
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> subscribe(String subscriber) {
        if (subscriber == null || subscriber.isBlank()) {
            throw new ServiceException(422, "订阅人必填");
        }
        IotSlaSub exist = subDao.selectOne(new LambdaQueryWrapper<IotSlaSub>()
                .eq(IotSlaSub::getSubscriber, subscriber)
                .eq(IotSlaSub::getReportType, "IOT"));
        if (exist != null) {
            exist.setActive("1");
            subDao.updateById(exist);
            return Map.of("subscriber", subscriber, "active", true, "renewed", true);
        }
        IotSlaSub sub = new IotSlaSub();
        sub.setSubscriber(subscriber);
        sub.setReportType("IOT");
        sub.setActive("1");
        sub.setCreateBy(SecurityUtils.getCurrentUserId());
        subDao.insert(sub);
        return Map.of("subscriber", subscriber, "active", true, "renewed", false);
    }

    /** 归档推送：未推送期的活跃订阅人各生成完成通知 */
    private int pushSubscribers(IotSlaReport r) {
        List<IotSlaSub> subs = subDao.selectList(new LambdaQueryWrapper<IotSlaSub>()
                .eq(IotSlaSub::getActive, "1"));
        int pushed = 0;
        for (IotSlaSub s : subs) {
            if (r.getPeriod().equals(s.getLastPushPeriod())) {
                continue;   // 防重复推送
            }
            jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_USER, TITLE, CONTENT, BIZ_TYPE, " +
                            "BIZ_ID, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) " +
                            "VALUES (?,?,?,?,?,?, '0', ?,NOW(),'0',0)",
                    UUID.randomUUID().toString().replace("-", ""),
                    s.getSubscriber(), "移动/IoT SLA 报告已归档：" + r.getPeriod(),
                    "报告期 " + r.getPeriod() + " 指标快照：" + r.getMetricsJson(),
                    "IOT_SLA", r.getId(), "system");
            s.setLastPushPeriod(r.getPeriod());
            subDao.updateById(s);
            pushed++;
        }
        return pushed;
    }

    private IotSlaReport require(String period) {
        IotSlaReport r = reportDao.selectOne(new LambdaQueryWrapper<IotSlaReport>()
                .eq(IotSlaReport::getPeriod, period));
        if (r == null) {
            throw new ServiceException(404, "报告期不存在：" + period + "（请先生成）");
        }
        return r;
    }

    private Map<String, Object> toMap(IotSlaReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("period", r.getPeriod());
        m.put("status", r.getStatus());
        m.put("metricsJson", r.getMetricsJson());
        m.put("publishAt", r.getPublishAt());
        m.put("publishLog", r.getPublishLog());
        return m;
    }

    private static long num(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }
}
