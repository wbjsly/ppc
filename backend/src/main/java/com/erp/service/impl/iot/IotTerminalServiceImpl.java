package com.erp.service.impl.iot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.iot.IotTerminalDao;
import com.erp.entity.iot.IotTerminal;
import com.erp.entity.system.SysUser;
import com.erp.dao.system.SysUserDao;
import com.erp.service.iot.IotTerminalService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 移动终端注册与签审双因子（add-iot-mobile，C-4.11-18）。
 * 未注册/证书吊销 → 403；连续 3 次密码失败 → 安全告警 + 终端锁定；管理员解锁留痕。
 */
@Slf4j
@Service
public class IotTerminalServiceImpl implements IotTerminalService {

    private static final int MAX_FAIL = 3;

    private final IotTerminalDao terminalDao;
    private final SysUserDao sysUserDao;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;

    public IotTerminalServiceImpl(IotTerminalDao terminalDao, SysUserDao sysUserDao,
                                  PasswordEncoder passwordEncoder, JdbcTemplate jdbc) {
        this.terminalDao = terminalDao;
        this.sysUserDao = sysUserDao;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> register(Map<String, String> body) {
        String code = body.get("deviceCode");
        if (code == null || code.isBlank()) {
            throw new ServiceException(422, "终端编码必填");
        }
        IotTerminal exists = terminalDao.selectOne(new LambdaQueryWrapper<IotTerminal>()
                .eq(IotTerminal::getDeviceCode, code));
        if (exists != null) {
            throw new ServiceException(409, "终端编码已注册");
        }
        IotTerminal t = new IotTerminal();
        t.setDeviceCode(code);
        t.setDeviceType(body.getOrDefault("deviceType", "PDA"));
        t.setImeiMac(body.get("imeiMac"));
        t.setBindUserId(body.get("bindUserId"));
        // 签发设备证书（自签 SN）
        t.setCertSn("CERT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        t.setCertStatus(IotTerminal.CERT_ACTIVE);
        t.setFailCnt(0);
        t.setLockedFlag("0");
        t.setRemark(body.get("remark"));
        terminalDao.insert(t);
        return detail(code);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> bind(String deviceCode, String userId) {
        IotTerminal t = requireActive(deviceCode);
        if (userId != null && sysUserDao.selectById(userId) == null) {
            throw new ServiceException(404, "绑定账号不存在");
        }
        t.setBindUserId(userId);
        terminalDao.updateById(t);
        return detail(deviceCode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> unbind(String deviceCode) {
        IotTerminal t = requireActive(deviceCode);
        t.setBindUserId(null);
        terminalDao.updateById(t);
        return detail(deviceCode);
    }

    /**
     * 签审双因子：注册态核验 + 二次输入密码（BCrypt 比对登录口令）。
     * 连续 3 次失败 → 写安全告警事件 + 锁定终端。
     * 注意：不能整体 @Transactional——失败计数/锁定/告警必须在抛 403 前独立提交，
     * 否则随异常回滚（每 DAO 操作独立自动提交）。
     */
    @Override
    public Map<String, Object> verifySign(Map<String, String> body) {
        String deviceCode = body.get("deviceCode");
        String password = body.get("password");
        IotTerminal t = terminalDao.selectOne(new LambdaQueryWrapper<IotTerminal>()
                .eq(IotTerminal::getDeviceCode, deviceCode));
        // 未注册终端 → 403（C-4.11-18 L1 硬阻断）
        if (t == null) {
            writeSecurityEvent(deviceCode, "未注册终端尝试签审");
            throw new ServiceException(403, "终端未注册，请联系系统管理员");
        }
        if (IotTerminal.CERT_REVOKED.equals(t.getCertStatus())) {
            throw new ServiceException(403, "终端证书已吊销");
        }
        if ("1".equals(t.getLockedFlag())) {
            throw new ServiceException(403, "终端已连续失败锁定，请联系管理员解锁");
        }
        String userId = SecurityUtils.getCurrentUserId();
        SysUser user = sysUserDao.selectById(userId);
        if (user == null) {
            throw new ServiceException(401, "未登录");
        }
        if (password == null || !passwordEncoder.matches(password, user.getPassword())) {
            int next = (t.getFailCnt() == null ? 0 : t.getFailCnt()) + 1;
            t.setFailCnt(next);
            if (next >= MAX_FAIL) {
                t.setLockedFlag("1");
                t.setLockReason("连续 " + MAX_FAIL + " 次签审密码失败");
                t.setLockAt(LocalDateTime.now());
                writeSecurityEvent(deviceCode, "连续签审密码失败 " + next + " 次，终端锁定");
            }
            terminalDao.updateById(t);
            throw new ServiceException(403, "签审密码校验失败（第 " + next + " 次）");
        }
        // 通过：清零失败计数
        t.setFailCnt(0);
        terminalDao.updateById(t);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pass", true);
        out.put("deviceCode", deviceCode);
        out.put("certSn", t.getCertSn());
        out.put("userId", userId);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> unlock(String deviceCode, String reason) {
        IotTerminal t = terminalDao.selectOne(new LambdaQueryWrapper<IotTerminal>()
                .eq(IotTerminal::getDeviceCode, deviceCode));
        if (t == null) {
            throw new ServiceException(404, "终端不存在");
        }
        t.setLockedFlag("0");
        t.setFailCnt(0);
        t.setLockReason(null);
        t.setUnlockBy(SecurityUtils.getCurrentUserId());
        t.setUnlockAt(LocalDateTime.now());
        t.setRemark((t.getRemark() == null ? "" : t.getRemark() + "；") + "解锁原因:" + reason);
        terminalDao.updateById(t);
        log.info("[iot] terminal unlocked: {} by {} reason={}", deviceCode, t.getUnlockBy(), reason);
        return detail(deviceCode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> revokeCert(String deviceCode) {
        IotTerminal t = requireActive(deviceCode);
        t.setCertStatus(IotTerminal.CERT_REVOKED);
        terminalDao.updateById(t);
        return detail(deviceCode);
    }

    @Override
    public List<Map<String, Object>> list(String keyword, String status) {
        LambdaQueryWrapper<IotTerminal> qw = new LambdaQueryWrapper<IotTerminal>()
                .like(keyword != null && !keyword.isBlank(), IotTerminal::getDeviceCode, keyword)
                .orderByDesc(IotTerminal::getCreateDate);
        List<Map<String, Object>> out = new ArrayList<>();
        for (IotTerminal t : terminalDao.selectList(qw)) {
            if (status != null && !status.isBlank() && !status.equals(t.getLockedFlag() == null ? "0" : t.getLockedFlag())) {
                continue;
            }
            out.add(toMap(t));
        }
        return out;
    }

    @Override
    public Map<String, Object> detail(String deviceCode) {
        return toMap(requireActive(deviceCode));
    }

    @Override
    public void touchOnline(String deviceCode) {
        IotTerminal t = terminalDao.selectOne(new LambdaQueryWrapper<IotTerminal>()
                .eq(IotTerminal::getDeviceCode, deviceCode));
        if (t != null) {
            t.setLastOnlineAt(LocalDateTime.now());
            terminalDao.updateById(t);
        }
    }

    // ---------- internal ----------

    private IotTerminal requireActive(String deviceCode) {
        IotTerminal t = terminalDao.selectOne(new LambdaQueryWrapper<IotTerminal>()
                .eq(IotTerminal::getDeviceCode, deviceCode));
        if (t == null) {
            throw new ServiceException(404, "终端不存在");
        }
        return t;
    }

    /** 安全事件写入告警表（连续失败告警，ALERT_TYPE=SECURITY） */
    private void writeSecurityEvent(String deviceCode, String reason) {
        jdbc.update("INSERT INTO erp_iot_alarm_event (ID, EVENT_NO, DEVICE_NO, ALERT_LEVEL, ALERT_TYPE, " +
                        "RULE_SNAP, MERGE_KEY, MERGE_CNT, STATUS, OCCURRED_AT, REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,NOW(),'0',0)",
                UUID.randomUUID().toString().replace("-", ""),
                "AL" + System.currentTimeMillis(), deviceCode, "L2", "SECURITY",
                reason, "SECURITY:" + deviceCode, 1, "OPEN", LocalDateTime.now(), reason,
                SecurityUtils.getCurrentUserId() == null ? "system" : SecurityUtils.getCurrentUserId());
    }

    private Map<String, Object> toMap(IotTerminal t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("deviceCode", t.getDeviceCode());
        m.put("deviceType", t.getDeviceType());
        m.put("imeiMac", t.getImeiMac());
        m.put("bindUserId", t.getBindUserId());
        m.put("certSn", t.getCertSn());
        m.put("certStatus", t.getCertStatus());
        m.put("lastOnlineAt", t.getLastOnlineAt());
        m.put("failCnt", t.getFailCnt());
        m.put("lockedFlag", t.getLockedFlag());
        m.put("lockReason", t.getLockReason());
        m.put("lockAt", t.getLockAt());
        m.put("unlockBy", t.getUnlockBy());
        m.put("unlockAt", t.getUnlockAt());
        m.put("remark", t.getRemark());
        m.put("createDate", t.getCreateDate());
        return m;
    }
}
