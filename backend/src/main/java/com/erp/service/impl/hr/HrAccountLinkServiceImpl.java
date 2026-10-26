package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrPositionDao;
import com.erp.dao.system.SysRoleDao;
import com.erp.dao.system.SysUserDao;
import com.erp.dao.system.SysUserRoleDao;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrPosition;
import com.erp.entity.system.SysRole;
import com.erp.entity.system.SysUser;
import com.erp.entity.system.SysUserRole;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.hr.HrAccountLinkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 人事账号联动实现（design D3：三触点直连，失败告警不回滚人事事务）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrAccountLinkServiceImpl implements HrAccountLinkService {

    private final HrEmployeeDao empDao;
    private final HrPositionDao posDao;
    private final SysUserDao userDao;
    private final SysUserRoleDao userRoleDao;
    private final SysRoleDao roleDao;
    private final ApprovalEngine approvalEngine;
    private final PasswordEncoder passwordEncoder;

    public static final String BIZ_ACCOUNT_OPEN = "HrAccountOpen";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onArchived(String empId) {
        HrEmployee e = empDao.selectById(empId);
        if (e == null) {
            throw new ServiceException(404, "员工档案不存在：" + empId);
        }
        if (userDao.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, e.getEmpNo())) > 0) {
            return; // 幂等：账号已存在
        }
        // 入职开通审批待办（HR 经理节点，design D3 触点①）
        approvalEngine.submit(BIZ_ACCOUNT_OPEN, empId,
                "入职开通账号：" + e.getEmpNo() + " " + e.getName(), "ROLE_HR_MGR",
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_HR_MGR", "HR 经理确认开通"))));
        log.info("account open approval submitted for {} {}", e.getEmpNo(), e.getName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createAccount(String empId) {
        HrEmployee e = empDao.selectById(empId);
        if (e == null) {
            throw new ServiceException(404, "员工档案不存在：" + empId);
        }
        SysUser exists = userDao.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, e.getEmpNo()).last("LIMIT 1"));
        if (exists != null) {
            return exists.getId(); // 幂等
        }
        SysUser u = new SysUser();
        u.setId(uuid());
        u.setUsername(e.getEmpNo());
        String initial = "Init@" + (e.getEmpNo().length() >= 4
                ? e.getEmpNo().substring(e.getEmpNo().length() - 4) : e.getEmpNo());
        u.setPassword(passwordEncoder.encode(initial));
        u.setNickName(e.getName());
        u.setStatus("1");
        u.setCreateBy("hr-link");
        userDao.insert(u);
        // 角色按岗位映射
        assignRoles(u.getId(), e.getPosId());
        // 回写主档账号锚
        e.setUserId(u.getId());
        e.setUpdateBy("hr-link");
        e.setUpdateDate(LocalDateTime.now());
        empDao.updateById(e);
        log.info("account created for {} (username {}, 初始密码 Init@后4位)", e.getEmpNo(), e.getEmpNo());
        return u.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String swapRoles(String empId, String oldPosId, String newPosId) {
        HrEmployee e = empDao.selectById(empId);
        if (e == null || e.getUserId() == null) {
            return null; // 无账号锚——不阻断人事事务
        }
        List<String> oldRoles = rolesOfPos(oldPosId);
        List<String> newRoles = rolesOfPos(newPosId);
        // 移除旧岗位角色（仅移除旧映射中且新映射不含的）
        for (String roleCode : oldRoles) {
            if (newRoles.contains(roleCode)) {
                continue;
            }
            SysRole role = roleDao.selectOne(new LambdaQueryWrapper<SysRole>()
                    .eq(SysRole::getRoleCode, roleCode).last("LIMIT 1"));
            if (role == null) {
                continue;
            }
            userRoleDao.delete(new LambdaQueryWrapper<SysUserRole>()
                    .eq(SysUserRole::getUserId, e.getUserId())
                    .eq(SysUserRole::getRoleId, role.getId()));
        }
        // 赋予新岗位角色
        for (String roleCode : newRoles) {
            if (oldRoles.contains(roleCode)) {
                continue;
            }
            SysRole role = roleDao.selectOne(new LambdaQueryWrapper<SysRole>()
                    .eq(SysRole::getRoleCode, roleCode).last("LIMIT 1"));
            if (role == null) {
                log.warn("role not found for code {}", roleCode);
                continue;
            }
            boolean bound = userRoleDao.selectCount(new LambdaQueryWrapper<SysUserRole>()
                    .eq(SysUserRole::getUserId, e.getUserId())
                    .eq(SysUserRole::getRoleId, role.getId())) > 0;
            if (!bound) {
                SysUserRole ur = new SysUserRole();
                ur.setId(uuid());
                ur.setUserId(e.getUserId());
                ur.setRoleId(role.getId());
                userRoleDao.insert(ur);
            }
        }
        log.info("roles swapped for {}: {} -> {}", e.getEmpNo(), oldRoles, newRoles);
        return e.getUserId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void disableAccount(String empId) {
        HrEmployee e = empDao.selectById(empId);
        if (e == null || e.getUserId() == null) {
            return;
        }
        SysUser u = userDao.selectById(e.getUserId());
        if (u == null) {
            return;
        }
        u.setStatus("0");
        u.setUpdateBy("hr-link");
        u.setUpdateDate(LocalDateTime.now());
        userDao.updateById(u);
        log.info("account disabled for {} ({})", e.getEmpNo(), u.getUsername());
    }

    // ================================================================
    // 内部
    // ================================================================

    private void assignRoles(String userId, String posId) {
        for (String roleCode : rolesOfPos(posId)) {
            SysRole role = roleDao.selectOne(new LambdaQueryWrapper<SysRole>()
                    .eq(SysRole::getRoleCode, roleCode).last("LIMIT 1"));
            if (role == null) {
                log.warn("role not found for code {}", roleCode);
                continue;
            }
            SysUserRole ur = new SysUserRole();
            ur.setId(uuid());
            ur.setUserId(userId);
            ur.setRoleId(role.getId());
            userRoleDao.insert(ur);
        }
    }

    private List<String> rolesOfPos(String posId) {
        if (posId == null || posId.isBlank()) {
            return List.of();
        }
        HrPosition p = posDao.selectById(posId);
        if (p == null || p.getRolesJson() == null || p.getRolesJson().isBlank()) {
            return List.of();
        }
        try {
            return new ArrayList<>(new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(p.getRolesJson(), java.util.ArrayList.class));
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
