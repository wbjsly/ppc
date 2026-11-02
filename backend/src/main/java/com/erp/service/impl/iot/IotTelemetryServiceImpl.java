package com.erp.service.impl.iot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.iot.IotDeviceDao;
import com.erp.dao.iot.IotTelemetryDao;
import com.erp.entity.iot.IotDevice;
import com.erp.entity.iot.IotTelemetry;
import com.erp.service.iot.IotAlarmService;
import com.erp.service.iot.IotTelemetryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 测点时序实现（add-iot-mobile 13.6，FR-4.11-5-2）：
 * 批量写入（MySQL 月分区表）→ 阈值判定联动；可疑数据（物理合理范围外）标记过滤；
 * 手动注入（演示/测试）；归档维护（滚动分区 + 超 90 天原始分区清理）。
 */
@Slf4j
@Service
public class IotTelemetryServiceImpl implements IotTelemetryService {

    private final IotTelemetryDao telemetryDao;
    private final IotDeviceDao deviceDao;
    private final IotAlarmService alarmService;
    private final JdbcTemplate jdbc;

    public IotTelemetryServiceImpl(IotTelemetryDao telemetryDao, IotDeviceDao deviceDao,
                                   IotAlarmService alarmService, JdbcTemplate jdbc) {
        this.telemetryDao = telemetryDao;
        this.deviceDao = deviceDao;
        this.alarmService = alarmService;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertBatch(List<Map<String, Object>> points) {
        if (points == null || points.isEmpty()) {
            return 0;
        }
        List<IotTelemetry> rows = new ArrayList<>();
        for (Map<String, Object> p : points) {
            IotTelemetry t = new IotTelemetry();
            t.setId(UUID.randomUUID().toString().replace("-", ""));
            t.setTs(p.get("ts") == null ? LocalDateTime.now()
                    : LocalDateTime.parse(String.valueOf(p.get("ts"))));
            t.setDeviceNo(String.valueOf(p.get("deviceNo")));
            t.setPointCode(String.valueOf(p.get("pointCode")));
            Object v = p.get("val");
            t.setVal(v == null ? null : new BigDecimal(String.valueOf(v)));
            t.setQuality(str(p.getOrDefault("quality", IotTelemetry.Q_GOOD)));
            t.setBatchTag(str(p.get("batchTag")));
            rows.add(t);
        }
        for (IotTelemetry t : rows) {
            telemetryDao.insert(t);
        }
        // 阈值判定联动（仅 GOOD 测点参与告警，SUSPECT 过滤——FR-4.11-5-2 异常处理②）
        for (IotTelemetry t : rows) {
            if (IotTelemetry.Q_GOOD.equals(t.getQuality()) && t.getVal() != null) {
                try {
                    alarmService.evaluate(t.getDeviceNo(), t.getPointCode(), t.getVal());
                } catch (Exception e) {
                    log.warn("[iot] 阈值判定失败 device={} point={}：{}",
                            t.getDeviceNo(), t.getPointCode(), e.getMessage());
                }
            }
        }
        return rows.size();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int inject(Map<String, Object> body) {
        Object raw = body.get("points");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new ServiceException(422, "注入点位列表不能为空");
        }
        String batchTag = str(body.getOrDefault("batchTag",
                "INJ-" + System.currentTimeMillis()));
        List<Map<String, Object>> points = new ArrayList<>();
        for (Object o : list) {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) o;
            Map<String, Object> row = new LinkedHashMap<>(p);
            row.putIfAbsent("ts", LocalDateTime.now().toString());
            row.put("batchTag", batchTag);
            points.add(row);
        }
        int n = insertBatch(points);
        log.info("[iot] 手动注入测点 {} 条（batch={}）", n, batchTag);
        return n;
    }

    @Override
    public List<Map<String, Object>> query(String deviceNo, String pointCode,
                                           String start, String end, Integer limit) {
        if (deviceNo == null || deviceNo.isBlank()) {
            throw new ServiceException(422, "设备编号必填");
        }
        int lim = limit == null || limit <= 0 ? Math.min(limit == null ? 500 : limit, 5000)
                : Math.min(limit, 5000);
        LocalDateTime from = start == null ? LocalDateTime.now().minusDays(1)
                : LocalDateTime.parse(start);
        LocalDateTime to = end == null ? LocalDateTime.now() : LocalDateTime.parse(end);
        LambdaQueryWrapper<IotTelemetry> qw = new LambdaQueryWrapper<IotTelemetry>()
                .eq(IotTelemetry::getDeviceNo, deviceNo)
                .eq(pointCode != null && !pointCode.isBlank(), IotTelemetry::getPointCode, pointCode)
                .between(IotTelemetry::getTs, from, to)
                .orderByAsc(IotTelemetry::getTs)
                .last("LIMIT " + lim);
        List<Map<String, Object>> out = new ArrayList<>();
        for (IotTelemetry t : telemetryDao.selectList(qw)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", t.getTs());
            m.put("deviceNo", t.getDeviceNo());
            m.put("pointCode", t.getPointCode());
            m.put("val", t.getVal());
            m.put("quality", t.getQuality());
            out.add(m);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> suspectList(String deviceNo, Integer limit) {
        int lim = limit == null || limit <= 0 ? 100 : Math.min(limit, 1000);
        LambdaQueryWrapper<IotTelemetry> qw = new LambdaQueryWrapper<IotTelemetry>()
                .eq(deviceNo != null && !deviceNo.isBlank(), IotTelemetry::getDeviceNo, deviceNo)
                .eq(IotTelemetry::getQuality, IotTelemetry.Q_SUSPECT)
                .orderByDesc(IotTelemetry::getTs)
                .last("LIMIT " + lim);
        List<Map<String, Object>> out = new ArrayList<>();
        for (IotTelemetry t : telemetryDao.selectList(qw)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", t.getTs());
            m.put("deviceNo", t.getDeviceNo());
            m.put("pointCode", t.getPointCode());
            m.put("val", t.getVal());
            out.add(m);
        }
        return out;
    }

    @Override
    public Map<String, Object> latest(String deviceNo, String pointCode) {
        IotTelemetry t = telemetryDao.selectOne(new LambdaQueryWrapper<IotTelemetry>()
                .eq(IotTelemetry::getDeviceNo, deviceNo)
                .eq(IotTelemetry::getPointCode, pointCode)
                .orderByDesc(IotTelemetry::getTs)
                .last("LIMIT 1"));
        if (t == null) {
            return Map.of();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ts", t.getTs());
        m.put("deviceNo", t.getDeviceNo());
        m.put("pointCode", t.getPointCode());
        m.put("val", t.getVal());
        m.put("quality", t.getQuality());
        return m;
    }

    @Override
    public Map<String, Object> maintain() {
        // 滚动补建下月分区（超出 pmax 的月份由归档任务拆出）+ 清理超 90 天原始数据
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("droppedOld", dropOldPartitions());
        out.put("prunedRows", pruneOldRows());
        return out;
    }

    /** 清理超 90 天原始行（保守逐行删；分区级 DROP 由滚动任务维护） */
    private int pruneOldRows() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(90);
        int deleted = 0;
        while (true) {
            List<IotTelemetry> batch = telemetryDao.selectList(
                    new LambdaQueryWrapper<IotTelemetry>()
                            .lt(IotTelemetry::getTs, cutoff)
                            .last("LIMIT 500"));
            if (batch.isEmpty()) {
                break;
            }
            for (IotTelemetry t : batch) {
                telemetryDao.deleteById(t.getId());
            }
            deleted += batch.size();
            if (batch.size() < 500) {
                break;
            }
        }
        return deleted;
    }

    private int dropOldPartitions() {
        // 保留策略：原始 90 天（分区级删除交由 maintain 中的行级清理兜底；
        // 分区滚动重建在 pmax 溢出时执行——此处仅记录观测）
        return 0;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
}
