package com.erp.service;

import java.util.List;
import java.util.Map;

/**
 * 认证业务能力契约。
 */
public interface AuthService {

    /** 登录校验并签发 JWT */
    Map<String, Object> login(String username, String password);

    /** 按 token 解析当前用户信息 */
    Map<String, Object> getUserInfo(String authHeader);

    /** 修改当前登录用户密码（旧密码校验 + 复杂度校验 + 清强制改密标记，design D3） */
    Map<String, Object> changePassword(String authHeader, String oldPwd, String newPwd);
}
