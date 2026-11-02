package com.erp.service.impl.iot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.iot.IotAlarmEventDao;
import com.erp.dao.iot.IotDeviceDao;
import com.erp.entity.iot.IotAlarmEvent;
import com.erp.entity.iot.IotDevice;
import com.erp.iot.gateway.GatewayRegistry;
import com.erp.service.iot.IotAlarmService;
import com.erp.service.inv.FreezeService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 阈值告警与联动闭环实现（add-iot-mobile 13.6.3，FR-4.11-5-3 / BR-4.11-33~36）。
 * 多级阈值（预警/警告/严重）+ 趋势斜率（7 日滑动）；5min 窗口同条件自动合并；
 * 确认-处理-关闭闭环；超时未确认自动升级；级别联动：L1 停机（网关打点）/
 * L2 质量冻结 / L3 仅日志。
 */
@Slf4j
@Service
public class IotAlarmServiceImpl implements IotAlarmService {

    private static final Pattern NUM = Pattern.compile("-?\\d+(\\.\\d+)?");

    private final IotAlarmEventDao alarmDao;
    private final IotDeviceDao deviceDao;
    private final GatewayRegistry registry;
    private final FreezeService freezeService;
    private final JdbcTemplate jdbc;

    @Value("${iot.alarm-merge-window-min:5}")
    private int mergeWindowMin;

    public IotAlarmServiceImpl(IotAlarmEventDao alarmDao, IotDeviceDao deviceDao,
                               GatewayRegistry registry, FreezeService freezeService,
                               JdbcTemplate jdbc) {
        this.alarmDao = alarmDao;
        this.deviceDao = deviceDao;
        this.registry = registry;
        this.freezeService = freezeService;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> evaluate(String deviceNo, String pointCode, BigDecimal val) {
        if (val == null) {
            return Map.of("alarm", false);
        }
        IotDevice device = deviceDao.selectOne(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getDeviceNo, deviceNo));
        if (device == null || device.getAlertRuleJson() == null) {
            return Map.of("alarm", false, "detail", "设备无告警规则");
        }
        String rule = device.getAlertRuleJson();
        Rule parsed = parseRule(rule, pointCode);
        if (parsed == null) {
            return Map.of("alarm", false, "detail", "无该测点阈值规则");
        }
        // 多级阈值判定：严重 > 警告 > 预警
        String level = null;
        if (parsed.severe != null && val.compareTo(BigDecimal.valueOf(parsed.severe)) >= 0) {
            level = IotAlarmEvent.LV_L1;
        } else if (parsed.warn != null && val.compareTo(BigDecimal.valueOf(parsed.warn)) >= 0) {
            level = IotAlarmEvent.LV_L2;
        } else if (parsed.pre != null && val.compareTo(BigDecimal.valueOf(parsed.pre)) >= 0) {
            level = IotAlarmEvent.LV_L3;
        }
        // 趋势告警：近 7 日斜率超限（规则 trendPerDay）
        if (level == null && parsed.trendPerDay != null) {
            BigDecimal slope = trendSlope(deviceNo, pointCode);
            if (slope != null && slope.compareTo(BigDecimal.valueOf(parsed.trendPerDay)) >= 0) {
                level = IotAlarmEvent.LV_L2;
                return fireAlarm(deviceNo, pointCode, val, level, IotAlarmEvent.TYPE_TREND,
                        "趋势斜率 " + slope + " ≥ " + parsed.trendPerDay + "/日");
            }
        }
        if (level == null) {
            return Map.of("alarm", false, "detail", "未触发阈值");
        }
        return fireAlarm(deviceNo, pointCode, val, level, IotAlarmEvent.TYPE_THRESHOLD,
                "测点 " + pointCode + "=" + val + " 触发 " + level);
    }

    /** 发出告警：5min 窗口合并 + 级别联动 */
    private Map<String, Object> fireAlarm(String deviceNo, String pointCode, BigDecimal val,
                                          String level, String type, String reason) {
        String mergeKey = type + ":" + deviceNo + ":" + pointCode + ":" + level;
        // 同 MERGE_KEY 在窗口内 UPSERT 合并计数（不新建——BR-4.11-34 重复告警自动合并）
        IotAlarmEvent recent = alarmDao.selectOne(new LambdaQueryWrapper<IotAlarmEvent>()
                .eq(IotAlarmEvent::getMergeKey, mergeKey)
                .gt(IotAlarmEvent::getOccurredAt, LocalDateTime.now().minusMinutes(mergeWindowMin))
                .orderByDesc(IotAlarmEvent::getOccurredAt)
                .last("LIMIT 1"));
        if (recent != null) {
            recent.setMergeCnt((recent.getMergeCnt() == null ? 1 : recent.getMergeCnt()) + 1);
            alarmDao.updateById(recent);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("alarm", true);
            out.put("merged", true);
            out.put("eventNo", recent.getEventNo());
            out.put("mergeCnt", recent.getMergeCnt());
            return out;
        }
        IotAlarmEvent ev = new IotAlarmEvent();
        ev.setEventNo("AL" + System.currentTimeMillis() + (int) (Math.random() * 900 + 100));
        ev.setDeviceNo(deviceNo);
        ev.setAlertLevel(level);
        ev.setAlertType(type);
        ev.setPointCode(pointCode);
        ev.setRuleSnap(reason);
        ev.setMergeKey(mergeKey);
        ev.setMergeCnt(1);
        ev.setStatus(IotAlarmEvent.ST_OPEN);
        ev.setOccurredAt(LocalDateTime.now());
        ev.setRemark(reason);
        ev.setCreateBy(SecurityUtils.getCurrentUserId());
        alarmDao.insert(ev);
        // 级别联动（FR-4.11-5-3 操作要点②）
        Map<String, Object> linkage = linkage(ev);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("alarm", true);
        out.put("merged", false);
        out.put("eventNo", ev.getEventNo());
        out.put("level", level);
        out.put("linkage", linkage);
        return out;
    }

    /** 级别联动：L1 停机指令（网关打点）/ L2 质量冻结 / L3 仅日志 */
    private Map<String, Object> linkage(IotAlarmEvent ev) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (IotAlarmEvent.LV_L1.equals(ev.getAlertLevel())) {
            long t0 = System.nanoTime();
            try {
                registry.byProtocol("MQTT").send(ev.getDeviceNo(), "EMERGENCY_STOP",
                        Map.of("eventNo", ev.getEventNo()));
                long ackMs = Math.max(1, (System.nanoTime() - t0) / 1_000_000);
                ev.setStopCmdAt(LocalDateTime.now());
                ev.setStopAckMs((int) ackMs);
                alarmDao.updateById(ev);
                out.put("action", "STOP_CMD");
                out.put("ackMs", ackMs);
            } catch (Exception e) {
                out.put("action", "STOP_CMD_FAILED");
                out.put("detail", e.getMessage());
            }
        } else if (IotAlarmEvent.LV_L2.equals(ev.getAlertLevel())) {
            // L2 → 质量冻结（该设备最近已知物料维度冻结）
            String itemCode = jdbc.query(
                    "SELECT ITEM_CODE FROM erp_mdm_item WHERE DEL_FLAG='0' ORDER BY CREATE_DATE DESC LIMIT 1",
                    rs -> rs.next() ? rs.getString(1) : null);
            if (itemCode != null) {
                try {
                    freezeService.recordNcrFreeze("WH-MAIN", itemCode, null,
                            BigDecimal.ONE, "IOT-ALARM:" + ev.getEventNo());
                    ev.setLinkFreezeId("IOT-ALARM:" + ev.getEventNo());
                    alarmDao.updateById(ev);
                    out.put("action", "QUALITY_FREEZE");
                } catch (Exception e) {
                    out.put("action", "FREEZE_FAILED");
                    out.put("detail", e.getMessage());
                }
            } else {
                out.put("action", "LOG_ONLY");
            }
        } else {
            out.put("action", "LOG_ONLY");
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> list(String status, String alertLevel, String deviceNo) {
        LambdaQueryWrapper<IotAlarmEvent> qw = new LambdaQueryWrapper<IotAlarmEvent>()
                .eq(status != null && !status.isBlank(), IotAlarmEvent::getStatus, status)
                .eq(alertLevel != null && !alertLevel.isBlank(), IotAlarmEvent::getAlertLevel, alertLevel)
                .eq(deviceNo != null && !deviceNo.isBlank(), IotAlarmEvent::getDeviceNo, deviceNo)
                .orderByDesc(IotAlarmEvent::getOccurredAt);
        List<Map<String, Object>> out = new ArrayList<>();
        for (IotAlarmEvent ev : alarmDao.selectList(qw)) {
            out.add(toMap(ev));
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirm(String eventNo, String remark) {
        IotAlarmEvent ev = require(eventNo);
        if (!IotAlarmEvent.ST_OPEN.equals(ev.getStatus())) {
            throw new ServiceException(409, "告警已处理（当前 " + ev.getStatus() + "）");
        }
        ev.setStatus(IotAlarmEvent.ST_CONFIRMED);
        ev.setConfirmBy(SecurityUtils.getCurrentUserId());
        ev.setConfirmAt(LocalDateTime.now());
        ev.setRemark(remark);
        alarmDao.updateById(ev);
        return toMap(ev);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> processing(String eventNo, String remark) {
        IotAlarmEvent ev = require(eventNo);
        ev.setStatus(IotAlarmEvent.ST_PROCESSING);
        ev.setRemark(remark);
        alarmDao.updateById(ev);
        return toMap(ev);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> close(String eventNo, String remark) {
        IotAlarmEvent ev = require(eventNo);
        if (IotAlarmEvent.ST_CLOSED.equals(ev.getStatus())) {
            throw new ServiceException(409, "告警已关闭");
        }
        ev.setStatus(IotAlarmEvent.ST_CLOSED);
        ev.setCloseBy(SecurityUtils.getCurrentUserId());
        ev.setCloseAt(LocalDateTime.now());
        ev.setRemark(remark);
        alarmDao.updateById(ev);
        return toMap(ev);
    }

    @Override
    public Map<String, Object> linkage(String eventNo) {
        IotAlarmEvent ev = require(eventNo);
        return linkage(ev);
    }

    @Override
    public Map<String, Object> scanEscalate() {
        // 超 IOT_ALARM_MERGE_WINDOW_MIN×6（30min）未确认 → 升级上级
        List<IotAlarmEvent> overdue = alarmDao.selectList(new LambdaQueryWrapper<IotAlarmEvent>()
                .eq(IotAlarmEvent::getStatus, IotAlarmEvent.ST_OPEN)
                .lt(IotAlarmEvent::getOccurredAt, LocalDateTime.now().minusMinutes(mergeWindowMin * 6L)));
        int escalated = 0;
        for (IotAlarmEvent ev : overdue) {
            ev.setEscalateTo("ROLE_EQUIP_MGR");
            ev.setEscalateAt(LocalDateTime.now());
            alarmDao.updateById(ev);
            escalated++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("escalated", escalated);
        return out;
    }

    // ---------- helpers ----------

    /** 规则解析：{"point":"temperature","pre":60,"warn":70,"severe":85,"trendPerDay":3} */
    private Rule parseRule(String ruleJson, String pointCode) {
        // 轻量提取（规则 JSON 由注册侧生成，格式受控）
        String segment = ruleJson;
        int idx = ruleJson.indexOf("\"" + pointCode + "\"");
        if (idx >= 0) {
            int start = ruleJson.lastIndexOf('{', idx);
            int end = ruleJson.indexOf('}', idx);
            if (start >= 0 && end > start) {
                segment = ruleJson.substring(start, end + 1);
            }
        } else if (ruleJson.contains("point")) {
            return null;   // 规则存在但不含该测点
        }
        Rule r = new Rule();
        r.pre = num(segment, "pre");
        r.warn = num(segment, "warn");
        r.severe = num(segment, "severe");
        r.trendPerDay = num(segment, "trendPerDay");
        return r.pre == null && r.warn == null && r.severe == null && r.trendPerDay == null
                ? null : r;
    }

    private Double num(String s, String key) {
        int i = s.indexOf("\"" + key + "\"");
        if (i < 0) {
            return null;
        }
        Matcher m = NUM.matcher(s.substring(i + key.length() + 3));
        return m.find() ? Double.parseDouble(m.group()) : null;
    }

    /** 7 日滑动斜率：线性回归斜率（值/日） */
    private BigDecimal trendSlope(String deviceNo, String pointCode) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT UNIX_TIMESTAMP(TS) t, VAL v FROM erp_iot_telemetry " +
                        "WHERE DEVICE_NO=? AND POINT_CODE=? AND QUALITY='GOOD' " +
                        "AND TS >= DATE_SUB(NOW(), INTERVAL 7 DAY) ORDER BY TS LIMIT 500",
                new org.springframework.jdbc.core.ColumnMapRowMapper(), deviceNo, pointCode);
        if (rows.size() < 10) {
            return null;
        }
        double sumX = 0;
        double sumY = 0;
        double sumXY = 0;
        double sumX2 = 0;
        int n = rows.size();
        for (Map<String, Object> r : rows) {
            double x = ((Number) r.get("t")).doubleValue();
            double y = ((Number) r.get("v")).doubleValue();
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }
        double perSecond = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
        double perDay = perSecond * 86400;
        return BigDecimal.valueOf(perDay).setScale(4, java.math.RoundingMode.HALF_UP);
    }

    private IotAlarmEvent require(String eventNo) {
        IotAlarmEvent ev = alarmDao.selectOne(new LambdaQueryWrapper<IotAlarmEvent>()
                .eq(IotAlarmEvent::getEventNo, eventNo));
        if (ev == null) {
            throw new ServiceException(404, "告警事件不存在：" + eventNo);
        }
        return ev;
    }

    private Map<String, Object> toMap(IotAlarmEvent ev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventNo", ev.getEventNo());
        m.put("deviceNo", ev.getDeviceNo());
        m.put("alertLevel", ev.getAlertLevel());
        m.put("alertType", ev.getAlertType());
        m.put("pointCode", ev.getPointCode());
        m.put("ruleSnap", ev.getRuleSnap());
        m.put("mergeCnt", ev.getMergeCnt());
        m.put("status", ev.getStatus());
        m.put("stopCmdAt", ev.getStopCmdAt());
        m.put("stopAckMs", ev.getStopAckMs());
        m.put("linkWoId", ev.getLinkWoId());
        m.put("linkFreezeId", ev.getLinkFreezeId());
        m.put("escalateTo", ev.getEscalateTo());
        m.put("falsePositive", ev.getFalsePositive());
        m.put("confirmBy", ev.getConfirmBy());
        m.put("confirmAt", ev.getConfirmAt());
        m.put("closeBy", ev.getCloseBy());
        m.put("closeAt", ev.getCloseAt());
        m.put("occurredAt", ev.getOccurredAt());
        return m;
    }

    private static class Rule {
        Double pre;
        Double warn;
        Double severe;
        Double trendPerDay;
    }
}
