package com.erp.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.iot.IotDeviceDao;
import com.erp.entity.iot.IotDevice;
import com.erp.service.iot.IotTelemetryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 测点仿真器（add-iot-mobile 13.6.3，proposal 决策 6：定时仿真器 + 异常注入）。
 * 按设备类型生成正常波动测点；可注入异常曲线（趋势超限触发 TREND 告警）与
 * 可疑数据（物理合理范围外 → SUSPECT 过滤）。IOT_SIM_ENABLED=OFF 时仅手动注入。
 */
@Slf4j
@Component
public class IotTelemetrySimulator {

    private final IotDeviceDao deviceDao;
    private final IotTelemetryService telemetryService;

    @Value("${iot.sim-enabled:true}")
    private boolean simEnabled;

    public IotTelemetrySimulator(IotDeviceDao deviceDao, IotTelemetryService telemetryService) {
        this.deviceDao = deviceDao;
        this.telemetryService = telemetryService;
    }

    /** 每 60s 为在线设备各生成一个采样点 */
    @Scheduled(fixedDelayString = "${iot.sim-interval-ms:60000}")
    public void tick() {
        if (!simEnabled) {
            return;
        }
        List<IotDevice> online = deviceDao.selectList(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getStatus, IotDevice.ST_ONLINE));
        List<Map<String, Object>> points = new ArrayList<>();
        for (IotDevice d : online) {
            points.addAll(sampleFor(d));
        }
        if (!points.isEmpty()) {
            telemetryService.insertBatch(points);
            log.debug("[iot] 仿真采样 {} 点（在线设备 {} 台）", points.size(), online.size());
        }
    }

    /** 按设备类型生成采样（temperature/vibration/current 三类测点） */
    private List<Map<String, Object>> sampleFor(IotDevice d) {
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        String devType = d.getDeviceType() == null ? "SENSOR" : d.getDeviceType();
        if ("SENSOR".equals(devType)) {
            // 温度：40~60℃ 正常波动 ±1.5；注入器可把 trendBias 推高触发阈值/趋势告警
            out.add(point(d.getDeviceNo(), "temperature",
                    base(50, 1.5, d), false, now));
            // 振动：0.5~2.0mm/s
            out.add(point(d.getDeviceNo(), "vibration",
                    base(1.2, 0.4, d), false, now));
        } else if ("PLC_GATEWAY".equals(devType)) {
            // 电流：10~20A
            out.add(point(d.getDeviceNo(), "current",
                    base(15, 2, d), false, now));
        } else {
            out.add(point(d.getDeviceNo(), "rfid-read-count",
                    BigDecimal.valueOf(ThreadLocalRandom.current().nextInt(80, 120)),
                    false, now));
        }
        // 可疑数据注入（物理合理范围外）：约 2% 概率 → SUSPECT 被告警过滤
        if (ThreadLocalRandom.current().nextInt(100) < 2) {
            out.add(point(d.getDeviceNo(), "temperature",
                    new BigDecimal("-999"), true, now));
        }
        return out;
    }

    /**
     * 基础值 + 均匀波动；注入趋势（trendBias）：告警规则 JSON 中 trendBias
     * 持续加温触发阈值/趋势判定（演示异常曲线）。
     */
    private BigDecimal base(double center, double spread, IotDevice d) {
        double bias = trendBias(d);
        double v = center + bias
                + ThreadLocalRandom.current().nextDouble(-spread, spread);
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    /** 从告警规则 JSON 读取注入偏置（演示：{"trendBias":2.5} 每分钟累加） */
    private double trendBias(IotDevice d) {
        String rule = d.getAlertRuleJson();
        if (rule == null || !rule.contains("trendBias")) {
            return 0;
        }
        try {
            int idx = rule.indexOf("trendBias") + "trendBias".length() + 2;
            int end = rule.indexOf(idx, '}') > 0 ? rule.indexOf('}', idx) : rule.length();
            String num = rule.substring(idx, end).replaceAll("[^0-9.\\-]", "");
            return Double.parseDouble(num);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private Map<String, Object> point(String deviceNo, String pointCode,
                                      BigDecimal val, boolean suspect, LocalDateTime ts) {
        return Map.of(
                "deviceNo", deviceNo,
                "pointCode", pointCode,
                "val", val,
                "quality", suspect ? "SUSPECT" : "GOOD",
                "ts", ts.toString());
    }

    /** 手动触发一次采样（冒烟/测试） */
    public int runOnce() {
        boolean prev = simEnabled;
        simEnabled = true;
        try {
            tick();
            return 1;
        } finally {
            simEnabled = prev;
        }
    }
}
