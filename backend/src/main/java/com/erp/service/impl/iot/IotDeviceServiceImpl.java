package com.erp.service.impl.iot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.iot.IotDeviceDao;
import com.erp.entity.iot.IotDevice;
import com.erp.iot.gateway.GatewayRegistry;
import com.erp.service.iot.IotDeviceService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IoT 设备接入实现（add-iot-mobile 13.6.1，FR-4.11-5-1 / C-4.11-18）：
 * 注册（协议/采集参数/告警规则）→ 连接测试（网关握手，证书无效 L1 拒绝）→
 * 心跳打点 → 状态分布。
 */
@Slf4j
@Service
public class IotDeviceServiceImpl implements IotDeviceService {

    private final IotDeviceDao deviceDao;
    private final GatewayRegistry registry;
    private final JdbcTemplate jdbc;

    public IotDeviceServiceImpl(IotDeviceDao deviceDao, GatewayRegistry registry,
                                JdbcTemplate jdbc) {
        this.deviceDao = deviceDao;
        this.registry = registry;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> register(Map<String, Object> body) {
        String deviceNo = str(body.get("deviceNo"));
        if (deviceNo == null || deviceNo.isBlank()) {
            throw new ServiceException(422, "设备编号必填");
        }
        IotDevice exist = deviceDao.selectOne(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getDeviceNo, deviceNo));
        if (exist != null) {
            throw new ServiceException(409, "设备编号已注册：" + deviceNo);
        }
        IotDevice d = new IotDevice();
        d.setDeviceNo(deviceNo);
        d.setDeviceType(str(body.getOrDefault("deviceType", "SENSOR")));
        d.setDeviceName(str(body.get("deviceName")));
        d.setProtocol(str(body.getOrDefault("protocol", "MQTT")));
        d.setProtocolPriority(priorityOf(d.getProtocol()));
        d.setSampleFreqSec(intOf(body.get("sampleFreqSec"), 60));
        d.setPointMapJson(str(body.get("pointMapJson")));
        d.setAlertRuleJson(str(body.get("alertRuleJson")));
        d.setStatus(IotDevice.ST_OFFLINE);
        // 设备证书：与终端同规则（CERT-* 前缀，无效 L1 拒绝接入）
        d.setCertSn(str(body.get("certSn")) != null ? str(body.get("certSn"))
                : "CERT-" + deviceNo);
        d.setCertStatus("ACTIVE");
        d.setAssetNo(str(body.get("assetNo")));
        d.setWorkArea(str(body.get("workArea")));
        d.setRemark(str(body.get("remark")));
        d.setCreateBy(SecurityUtils.getCurrentUserId());
        deviceDao.insert(d);
        return toMap(d);
    }

    @Override
    public Map<String, Object> connectTest(String deviceNo) {
        IotDevice d = require(deviceNo);
        // C-4.11-18：证书无效 L1 拒绝接入
        if (!"ACTIVE".equals(d.getCertStatus())) {
            throw new ServiceException(409, "【L1 硬阻断】设备证书无效（"
                    + d.getCertStatus() + "），拒绝接入（C-4.11-18）");
        }
        // 经网关适配器握手（协议编解码 + 证书校验在仿真驱动内）
        Map<String, Object> hs = registry.byProtocol(d.getProtocol())
                .handshake(d.getCertSn(), deviceNo);
        d.setStatus(IotDevice.ST_ONLINE);
        d.setHeartbeatAt(LocalDateTime.now());
        deviceDao.updateById(d);
        Map<String, Object> out = new LinkedHashMap<>(toMap(d));
        out.put("handshake", hs);
        return out;
    }

    @Override
    public Map<String, Object> heartbeat(String deviceNo) {
        IotDevice d = require(deviceNo);
        if (!"ACTIVE".equals(d.getCertStatus())) {
            throw new ServiceException(409, "【L1 硬阻断】设备证书无效，心跳拒绝");
        }
        d.setHeartbeatAt(LocalDateTime.now());
        if (!IotDevice.ST_ONLINE.equals(d.getStatus())) {
            d.setStatus(IotDevice.ST_ONLINE);
        }
        deviceDao.updateById(d);
        return toMap(d);
    }

    @Override
    public List<Map<String, Object>> list(String keyword, String status, String deviceType) {
        LambdaQueryWrapper<IotDevice> qw = new LambdaQueryWrapper<IotDevice>()
                .like(keyword != null && !keyword.isBlank(), IotDevice::getDeviceNo, keyword)
                .eq(status != null && !status.isBlank(), IotDevice::getStatus, status)
                .eq(deviceType != null && !deviceType.isBlank(), IotDevice::getDeviceType, deviceType)
                .orderByDesc(IotDevice::getCreateDate);
        List<Map<String, Object>> out = new ArrayList<>();
        for (IotDevice d : deviceDao.selectList(qw)) {
            out.add(toMap(d));
        }
        return out;
    }

    @Override
    public Map<String, Object> detail(String deviceNo) {
        return toMap(require(deviceNo));
    }

    @Override
    public Map<String, Object> statusDistribution() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String st : new String[]{IotDevice.ST_ONLINE, IotDevice.ST_OFFLINE,
                IotDevice.ST_FAULT, IotDevice.ST_MAINT}) {
            Long cnt = deviceDao.selectCount(new LambdaQueryWrapper<IotDevice>()
                    .eq(IotDevice::getStatus, st));
            out.put(st, cnt);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateAlertRule(String deviceNo, String alertRuleJson) {
        IotDevice d = require(deviceNo);
        d.setAlertRuleJson(alertRuleJson);
        d.setUpdateBy(SecurityUtils.getCurrentUserId());
        deviceDao.updateById(d);
        return toMap(d);
    }

    // ---------- internal ----------

    private IotDevice require(String deviceNo) {
        IotDevice d = deviceDao.selectOne(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getDeviceNo, deviceNo));
        if (d == null) {
            throw new ServiceException(404, "设备不存在：" + deviceNo);
        }
        return d;
    }

    /** 协议优先级 OPC-UA=1 > MQTT=2 > Modbus TCP=3 > RTU=4（FR-4.11-5-1 要点②） */
    private int priorityOf(String protocol) {
        return switch (protocol == null ? "MQTT" : protocol) {
            case "OPC-UA" -> 1;
            case "MQTT" -> 2;
            case "MODBUS_TCP" -> 3;
            case "MODBUS_RTU" -> 4;
            default -> 9;
        };
    }

    private Map<String, Object> toMap(IotDevice d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("deviceNo", d.getDeviceNo());
        m.put("deviceType", d.getDeviceType());
        m.put("deviceName", d.getDeviceName());
        m.put("protocol", d.getProtocol());
        m.put("protocolPriority", d.getProtocolPriority());
        m.put("sampleFreqSec", d.getSampleFreqSec());
        m.put("alertRuleJson", d.getAlertRuleJson());
        m.put("pointMapJson", d.getPointMapJson());
        m.put("status", d.getStatus());
        m.put("heartbeatAt", d.getHeartbeatAt());
        m.put("certSn", d.getCertSn());
        m.put("certStatus", d.getCertStatus());
        m.put("assetNo", d.getAssetNo());
        m.put("workArea", d.getWorkArea());
        m.put("createDate", d.getCreateDate());
        return m;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static int intOf(Object v, int def) {
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
