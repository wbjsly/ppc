package com.erp.service.intf;

import java.util.List;
import java.util.Map;

/**
 * 内部 SSO（本系统作 IdP，add-intf-portal-menus，spec interface-sso）。
 * ERP 作为身份提供方向兄弟系统（OA/CRM）签发限定 audience/有效期/claims 的
 * JWT SSO 断言（复用 JwtTokenProvider，零新依赖）；含兄弟系统 IdP 注册、
 * 断言签发、验签元数据、登出联动，全程审计留痕。
 */
public interface SsoService {

    /** 注册兄弟系统（ADMIN；APP_ID 唯一） */
    Map<String, Object> registerApp(Map<String, Object> payload);

    /** 兄弟系统列表 */
    List<Map<String, Object>> listApps();

    /** 停用/启用兄弟系统 */
    void changeAppStatus(String appId, String status);

    /**
     * 签发 SSO 断言：校验兄弟系统已注册且 ACTIVE → 用 ERP 身份换取
     * 限定 audience 的短有效期 JWT（默认 5 分钟，参数可调）。
     * 未注册/停用 → 422 拒绝并留痕。
     */
    Map<String, Object> issueAssertion(String appId);

    /** IdP 验签元数据（供兄弟系统本地验签的公钥/issuer） */
    Map<String, Object> metadata();

    /** 登出联动：向该用户活跃会话关联的兄弟系统推送登出通知；返回通知数 */
    int logoutNotify();
}
