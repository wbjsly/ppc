package com.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.dao.system.SysRoleDao;
import com.erp.dao.system.SysUserDao;
import com.erp.dao.system.SysUserRoleDao;
import com.erp.entity.system.SysRole;
import com.erp.entity.system.SysUser;
import com.erp.entity.system.SysUserRole;
import com.erp.service.hr.HrAccountLinkService;
import com.erp.service.impl.system.AccountLifecycleServiceImpl;
import com.erp.service.system.AccountLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 账号运维操作台（add-account-lifecycle-console，spec account-lifecycle R6，16.1 四叶）。
 * 全端点限 ADMIN（SecurityConfig 细规则）；审计留痕复用 hr_change_log（EMP_ID 锚 userId）。
 */
@Tag(name = "系统-账号运维")
@RestController
@RequestMapping("/api/system/accounts")
public class SysAccountController {

    private final SysUserDao userDao;
    private final SysUserRoleDao userRoleDao;
    private final SysRoleDao roleDao;
    private final AccountLifecycleService lifecycleService;
    private final HrAccountLinkService hrAccountLinkService;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final com.erp.service.system.PositionBaselineService baselineService;
    private final com.erp.service.PermRequestService permRequestService;

    public SysAccountController(SysUserDao userDao, SysUserRoleDao userRoleDao, SysRoleDao roleDao,
                                AccountLifecycleService lifecycleService,
                                HrAccountLinkService hrAccountLinkService,
                                PasswordEncoder passwordEncoder, JdbcTemplate jdbc,
                                com.erp.service.system.PositionBaselineService baselineService,
                                com.erp.service.PermRequestService permRequestService) {
        this.userDao = userDao;
        this.userRoleDao = userRoleDao;
        this.roleDao = roleDao;
        this.lifecycleService = lifecycleService;
        this.hrAccountLinkService = hrAccountLinkService;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.baselineService = baselineService;
        this.permRequestService = permRequestService;
    }

    @Operation(summary = "账号分页（status/keyword，JOIN 员工档案）")
    @GetMapping
    public R<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "20") long size,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<SysUser> qw = new LambdaQueryWrapper<SysUser>()
                .eq(org.springframework.util.StringUtils.hasText(status), SysUser::getStatus, status)
                .and(org.springframework.util.StringUtils.hasText(keyword), w -> w
                        .like(SysUser::getUsername, keyword)
                        .or().like(SysUser::getNickName, keyword))
                .orderByDesc(SysUser::getCreateDate);
        Page<SysUser> page = userDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> out = new Page<>(page.getCurrent(), page.getTotal(), page.getSize());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SysUser u : page.getRecords()) {
            rows.add(toRow(u));
        }
        out.setRecords(rows);
        return R.ok(out);
    }

    @Operation(summary = "账号详情（角色清单 + 员工锚）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        SysUser u = require(id);
        Map<String, Object> m = toRow(u);
        m.put("roleCodes", userRoleDao.getUserRoleCodes(id));
        return R.ok(m);
    }

    @Operation(summary = "重置密码（随机新密码一次性返回 + 强制改密标记）")
    @PostMapping("/{id}/reset-password")
    public R<Map<String, Object>> resetPassword(@PathVariable String id) {
        SysUser u = require(id);
        if (SysUser.ST_ARCHIVED.equals(u.getStatus())) {
            throw new ServiceException(422, "归档账号不可操作");
        }
        String raw = "Rst@" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        u.setPassword(passwordEncoder.encode(raw));
        u.setMustChangePwd("1");
        userDao.updateById(u);
        audit(id, "PWD_RESET", "-", "(一次性展示)", operator());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", id);
        out.put("newPassword", raw); // 一次性展示，不落日志不二次可查
        out.put("mustChangePwd", true);
        return R.ok(out);
    }

    @Operation(summary = "角色全量调整（前后集合 diff 审计）；超基线授予 → 转例外审批（BR-5.6-03）")
    @PutMapping("/{id}/roles")
    public R<Map<String, Object>> adjustRoles(@PathVariable String id,
                                              @RequestBody Map<String, Object> body) {
        SysUser u = require(id);
        if (SysUser.ST_ARCHIVED.equals(u.getStatus())) {
            throw new ServiceException(422, "归档账号不可操作");
        }
        Object rawIds = body.get("roleIds");
        if (!(rawIds instanceof List)) {
            throw new ServiceException(422, "roleIds 须为数组");
        }
        List<String> before = userRoleDao.getUserRoleCodes(id);

        // 解析目标角色全集（roleIds → codes）
        List<String> target = new ArrayList<>();
        for (Object rid : (List<?>) rawIds) {
            String roleId = String.valueOf(rid);
            SysRole role = roleDao.selectById(roleId);
            if (role == null) {
                throw new ServiceException(422, "角色不存在：" + roleId);
            }
            target.add(role.getRoleCode());
        }

        // 岗位基线（hr_employee.USER_ID 锚 → POS_ID → 基线表；无锚/无岗位 = 空基线）
        List<String> baseline = baselineFor(id);
        List<String> added = new ArrayList<>(target);
        added.removeAll(before);
        List<String> beyond = new ArrayList<>(added);
        beyond.removeAll(baseline);

        if (!beyond.isEmpty()) {
            // 拒绝默认：整单转例外审批（KIND=EXCEPTION，add-permission-approval-flow D7 改道，
            // 风险路由 + 有效期 + 台账），通过后 PermRequestApprovalCallback 落库
            Object reason = body.get("reason");
            var req = permRequestService.submitException(id, target, beyond,
                    reason == null ? null : String.valueOf(reason));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("userId", id);
            out.put("status", "PENDING_APPROVAL");
            out.put("requestId", req.get("id"));
            out.put("before", before);
            out.put("target", target);
            out.put("beyond", beyond);
            out.put("message", "含超基线角色，已提交例外审批（" + String.join("/", beyond)
                    + "），通过后生效");
            return R.ok(out);
        }

        // 基线内授予 / 移除：直接生效（全量替换）
        userRoleDao.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id));
        List<String> after = new ArrayList<>();
        for (String code : target) {
            SysRole role = roleDao.selectOne(new LambdaQueryWrapper<SysRole>()
                    .eq(SysRole::getRoleCode, code).last("LIMIT 1"));
            if (role == null) {
                throw new ServiceException(422, "角色不存在：" + code);
            }
            SysUserRole ur = new SysUserRole();
            ur.setId(UUID.randomUUID().toString().replace("-", ""));
            ur.setUserId(id);
            ur.setRoleId(role.getId());
            userRoleDao.insert(ur);
            after.add(code);
        }
        audit(id, "ROLES_ADJUSTED", String.join(",", before), String.join(",", after), operator());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", id);
        out.put("before", before);
        out.put("after", after);
        return R.ok(out);
    }

    /** 用户岗位基线角色集合（无员工锚/无岗位 → 空集合） */
    private List<String> baselineFor(String userId) {
        List<String> posIds = jdbc.queryForList(
                "SELECT POS_ID FROM hr_employee WHERE USER_ID = ? AND DEL_FLAG = '0' "
                        + "AND POS_ID IS NOT NULL LIMIT 1", String.class, userId);
        if (posIds.isEmpty()) {
            return List.of();
        }
        return baselineService.rolesOfPos(posIds.get(0));
    }

    @Operation(summary = "人工建号兜底（对已有员工档案未建号者，幂等）")
    @PostMapping("/manual-create")
    public R<Map<String, Object>> manualCreate(@RequestBody Map<String, String> body) {
        String empId = body.get("empId");
        if (!org.springframework.util.StringUtils.hasText(empId)) {
            throw new ServiceException(422, "empId 必填");
        }
        String userId = hrAccountLinkService.createAccount(empId);
        audit(userId, "MANUAL_ACCOUNT_CREATE", "-", "empId=" + empId, operator());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", userId);
        SysUser u = userDao.selectById(userId);
        out.put("username", u == null ? null : u.getUsername());
        return R.ok(out);
    }

    @Operation(summary = "强停（置 0 + 权限清单快照审计）")
    @PostMapping("/{id}/disable")
    public R<Map<String, Object>> disable(@PathVariable String id) {
        return R.ok(lifecycleService.disable(id, operator()));
    }

    @Operation(summary = "归档（输入目标用户名二次确认；仅停用态；不可恢复）")
    @PostMapping("/{id}/archive")
    public R<Map<String, Object>> archive(@PathVariable String id,
                                          @RequestBody Map<String, String> body) {
        return R.ok(lifecycleService.archive(id, body.get("confirmUsername"), operator()));
    }

    @Operation(summary = "生命周期清扫手动触发（离职 24h 强停 + 停用 90 天归档）")
    @PostMapping("/sweep")
    public R<Map<String, Object>> sweep() {
        return R.ok(lifecycleService.sweep());
    }

    // ---------- 内部 ----------

    private SysUser require(String id) {
        SysUser u = userDao.selectById(id);
        if (u == null) {
            throw new ServiceException(404, "账号不存在");
        }
        return u;
    }

    private Map<String, Object> toRow(SysUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("nickName", u.getNickName());
        m.put("status", u.getStatus());
        m.put("phone", u.getPhone());
        m.put("lastLoginAt", u.getLastLoginAt());
        m.put("mustChangePwd", "1".equals(u.getMustChangePwd()));
        m.put("archivedAt", u.getArchivedAt());
        m.put("createDate", u.getCreateDate());
        // 员工档案锚（LEFT JOIN 语义，经 JdbcTemplate 单查）
        List<Map<String, Object>> emp = jdbc.queryForList(
                "SELECT ID, EMP_NO, NAME, POS_ID, DEPT_ID FROM hr_employee WHERE USER_ID = ? AND DEL_FLAG = '0'",
                u.getId());
        m.put("employee", emp.isEmpty() ? null : emp.get(0));
        return m;
    }

    private void audit(String userId, String action, String oldVal, String newVal, String operator) {
        jdbc.update("INSERT INTO hr_change_log (ID, EMP_ID, FIELD_NAME, OLD_VALUE, NEW_VALUE, REASON, "
                        + "CHANGE_BY, CHANGE_AT) VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString().replace("-", ""), userId, action, oldVal, newVal,
                "account-console", operator, LocalDateTime.now());
    }

    private String operator() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }
}
