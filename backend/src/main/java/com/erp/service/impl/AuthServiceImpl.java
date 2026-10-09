package com.erp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.system.SysUserDao;
import com.erp.dao.system.SysUserRoleDao;
import com.erp.entity.system.SysUser;
import com.erp.security.JwtTokenProvider;
import com.erp.service.AuthService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 认证业务逻辑：登录签发 token、根据 token 获取用户信息。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private final PasswordEncoder passwordEncoder;
    private final SysUserDao sysUserDao;
    private final SysUserRoleDao sysUserRoleDao;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthServiceImpl(PasswordEncoder passwordEncoder,
                           SysUserDao sysUserDao,
                           SysUserRoleDao sysUserRoleDao,
                           JwtTokenProvider jwtTokenProvider) {
        this.passwordEncoder = passwordEncoder;
        this.sysUserDao = sysUserDao;
        this.sysUserRoleDao = sysUserRoleDao;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public Map<String, Object> login(String username, String password) {
        SysUser user = sysUserDao.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));

        if (user == null) {
            throw new ServiceException(401, "用户不存在: " + username);
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new ServiceException(401, "用户名或密码错误");
        }
        if (!"1".equals(user.getStatus())) {
            throw new ServiceException(403, "用户已禁用");
        }

        List<String> roleCodes = sysUserRoleDao.getUserRoleCodes(user.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("token", jwtTokenProvider.generateToken(user.getId(),
                Map.of("username", username, "roles", roleCodes)));
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        result.put("nickName", user.getNickName());
        result.put("roles", roleCodes);
        return result;
    }

    @Override
    public Map<String, Object> getUserInfo(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new ServiceException(401, "未登录");
        }

        String token = authHeader.substring(7);
        String userId;
        try {
            userId = jwtTokenProvider.getSubjectFromToken(token);
        } catch (Exception e) {
            throw new ServiceException(401, "无效的token");
        }

        SysUser user = sysUserDao.selectById(userId);
        if (user == null) {
            throw new ServiceException(404, "用户不存在");
        }

        List<String> roleCodes = sysUserRoleDao.getUserRoleCodes(user.getId());

        Map<String, Object> info = new HashMap<>();
        info.put("userId", user.getId());
        info.put("username", user.getUsername());
        info.put("nickName", user.getNickName());
        info.put("roles", roleCodes);
        info.put("permissions", Collections.emptyList());
        return info;
    }
}
