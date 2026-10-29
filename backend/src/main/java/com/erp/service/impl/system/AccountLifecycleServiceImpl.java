package com.erp.service.impl.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.system.SysUserDao;
import com.erp.dao.system.SysUserRoleDao;
import com.erp.entity.system.SysUser;
import com.erp.service.scm.CpfrClockService;
import com.erp.service.system.AccountLifecycleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 账号生命周期实现（add-account-lifecycle-console，design D4/D5/D7）：
 * 强停/归档 + sweep 两步幂等（离职 24h 兜底强停 BR-5.6-09、停用 90 天自动归档）；
 * 审计留痕复用 hr_change_log（EMP_ID 锚 userId——design D7，无通用审计表不新建）。
 */
@Slf4j
@Service
public class AccountLifecycleServiceImpl implements AccountLifecycleService {

    private final SysUserDao userDao;
    private final SysUserRoleDao userRoleDao;
    private final JdbcTemplate jdbc;
    private final CpfrClockService clock;

    /** 停用转归档天数（design D5，默认 90） */
    @Value("${app.admin.account-archive-days:90}")
    private int archiveDays;

    public AccountLifecycleServiceImpl(SysUserDao userDao, SysUserRoleDao userRoleDao,
                                       JdbcTemplate jdbc, CpfrClockService clock) {
        this.userDao = userDao;
        this.userRoleDao = userRoleDao;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Map<String, Object> disable(String userId, String operator) {
        SysUser u = require(userId);
        if (SysUser.ST_ARCHIVED.equals(u.getStatus())) {
            throw new ServiceException(422, "归档账号不可操作");
        }
        if (SysUser.ST_DISABLED.equals(u.getStatus())) {
            return Map.of("userId", userId, "roleSnapshot", roleSnapshot(userId), "already", true);
        }
        u.setStatus(SysUser.ST_DISABLED);
        userDao.updateById(u);
        String snapshot = roleSnapshot(userId);
        audit(userId, "ACCOUNT_DISABLED", SysUser.ST_ACTIVE, SysUser.ST_DISABLED,
                "roleSnapshot=" + snapshot, operator);
        log.info("account disabled: {} by {}", u.getUsername(), operator);
        return Map.of("userId", userId, "roleSnapshot", snapshot);
    }

    @Override
    @Transactional
    public Map<String, Object> archive(String userId, String confirmUsername, String operator) {
        SysUser u = require(userId);
        if (SysUser.ST_ARCHIVED.equals(u.getStatus())) {
            throw new ServiceException(422, "账号已归档（归档不可恢复，C-0-05）");
        }
        if (!SysUser.ST_DISABLED.equals(u.getStatus())) {
            throw new ServiceException(422, "仅停用账号可归档，请先执行强停");
        }
        if (!u.getUsername().equals(confirmUsername)) {
            throw new ServiceException(422, "二次确认用户名不一致，归档被拒绝");
        }
        u.setStatus(SysUser.ST_ARCHIVED);
        u.setArchivedAt(clock.now());
        userDao.updateById(u);
        audit(userId, "ACCOUNT_ARCHIVED", SysUser.ST_DISABLED, SysUser.ST_ARCHIVED,
                "confirmUsername=" + confirmUsername + "，归档不可恢复", operator);
        log.info("account archived: {} by {}", u.getUsername(), operator);
        Map<String, Object> out = new HashMap<>();
        out.put("userId", userId);
        out.put("status", SysUser.ST_ARCHIVED);
        out.put("archivedAt", u.getArchivedAt());
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> sweep() {
        LocalDateTime now = clock.now();
        // ①离职最后工作日 +24h 未停用 → 强停 + 告警审计（BR-5.6-09）
        // 最后工作日结束时刻 = QUIT_DATE+1 天 00:00；+24h → QUIT_DATE+2 天 00:00
        List<Map<String, Object>> overdue = jdbc.queryForList(
                "SELECT u.ID AS userId, u.USERNAME AS username, x.EMP_NAME AS empName, "
                        + "x.QUIT_DATE AS quitDate FROM hr_exit x "
                        + "JOIN hr_employee e ON e.ID = x.EMP_ID AND e.DEL_FLAG = '0' "
                        + "JOIN erp_admin_user u ON u.ID = e.USER_ID AND u.DEL_FLAG = '0' "
                        + "WHERE u.STATUS = '1' AND x.DEL_FLAG = '0' "
                        + "AND DATE_ADD(DATE_ADD(x.QUIT_DATE, INTERVAL 1 DAY), INTERVAL 1 DAY) <= ?",
                now);
        int disabled = 0;
        for (Map<String, Object> row : overdue) {
            String userId = String.valueOf(row.get("userId"));
            disable(userId, "account-sweep");
            audit(userId, "SWEEP_ALERT", SysUser.ST_ACTIVE, SysUser.ST_DISABLED,
                    "BR-5.6-09 离职超 24h 未停用，系统强制停用；最后工作日=" + row.get("quitDate"),
                    "account-sweep");
            disabled++;
        }
        // ②停用满 N 天 → 自动归档
        List<String> staleIds = jdbc.queryForList(
                "SELECT ID FROM erp_admin_user WHERE STATUS = '0' AND DEL_FLAG = '0' "
                        + "AND UPDATE_DATE <= DATE_SUB(?, INTERVAL ? DAY)",
                String.class, now, archiveDays);
        int archived = 0;
        for (String userId : staleIds) {
            SysUser u = userDao.selectById(userId);
            if (u == null || !SysUser.ST_DISABLED.equals(u.getStatus())) {
                continue; // 并发防护
            }
            u.setStatus(SysUser.ST_ARCHIVED);
            u.setArchivedAt(now);
            userDao.updateById(u);
            audit(userId, "ACCOUNT_ARCHIVED", SysUser.ST_DISABLED, SysUser.ST_ARCHIVED,
                    "停用满 " + archiveDays + " 天自动归档（不可恢复）", "account-sweep");
            archived++;
        }
        Map<String, Object> out = new HashMap<>();
        out.put("disabled", disabled);
        out.put("archived", archived);
        return out;
    }

    /** 角色清单 JSON 快照（《离职人员权限清单》语义） */
    String roleSnapshot(String userId) {
        List<String> roles = userRoleDao.getUserRoleCodes(userId);
        return "[" + String.join(",", roles) + "]";
    }

    void audit(String userId, String action, String oldVal, String newVal, String reason, String operator) {
        jdbc.update("INSERT INTO hr_change_log (ID, EMP_ID, FIELD_NAME, OLD_VALUE, NEW_VALUE, REASON, "
                        + "CHANGE_BY, CHANGE_AT) VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString().replace("-", ""), userId, action,
                oldVal, newVal, reason, operator, clock.now());
    }

    SysUser require(String userId) {
        SysUser u = userDao.selectById(userId);
        if (u == null) {
            throw new ServiceException(404, "账号不存在");
        }
        return u;
    }
}
