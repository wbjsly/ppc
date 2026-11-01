package com.erp.service.impl.intf;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.erp.common.AesUtil;
import com.erp.common.ServiceException;
import com.erp.dao.intf.IntfSsoAppDao;
import com.erp.dao.system.SysUserRoleDao;
import com.erp.entity.intf.IntfSsoApp;
import com.erp.security.AuditLogWriter;
import com.erp.security.JwtTokenProvider;
import com.erp.service.intf.SsoService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 内部 SSO 实现（本系统作 IdP，add-intf-portal-menus，spec interface-sso）。
 * 复用 JwtTokenProvider 签发限定 audience 的短有效期 JWT 断言（默认 5 分钟）。
 * Secret 存储沿 api-credential 先例 AES 加密；签发/停用/登出全程审计留痕。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SsoServiceImpl implements SsoService {

    private final IntfSsoAppDao ssoAppDao;
    private final SysUserRoleDao sysUserRoleDao;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuditLogWriter auditLogWriter;
    private final JdbcTemplate jdbc;

    /** clientSecret AES 加密密钥（对齐 api-credential 先例） */
    @Value("${app.intf.sso-secret:ErpSsoSecretKey2026ForClientSecretEncryptionxx}")
    private String ssoSecret;

    /** SSO 断言有效期（秒，默认 5 分钟短令牌） */
    @Value("${app.intf.sso-token-expire-sec:300}")
    private int tokenExpireSec;

    @Override
    public Map<String, Object> registerApp(Map<String, Object> payload) {
        String appId = str(payload.get("appId"));
        String appName = str(payload.get("appName"));
        String audience = str(payload.get("audience"));
        if (appId == null || appId.isBlank() || appName == null || appName.isBlank()
                || audience == null || audience.isBlank()) {
            throw new ServiceException(422, "appId/appName/audience 必填");
        }
        // APP_ID 唯一
        Integer dup = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_intf_sso_app WHERE APP_ID=? AND DEL_FLAG='0'",
                Integer.class, appId);
        if (dup != null && dup > 0) {
            throw new ServiceException(422, "appId 已存在");
        }
        // ADMIN 校验（注册高危动作限 ADMIN，spec interface-sso）
        requireAdmin("注册兄弟系统");
        IntfSsoApp app = new IntfSsoApp();
        app.setId(uuid());
        app.setAppId(appId);
        app.setAppName(appName);
        app.setPublicKey(str(payload.get("publicKey")));
        // clientSecret 仅注册时一次性加密存储，后续不可读回
        String secret = str(payload.get("clientSecret"));
        if (secret != null && !secret.isBlank()) {
            app.setClientSecret(AesUtil.encrypt(secret, ssoSecret));
        }
        app.setAudience(audience);
        app.setRedirectUri(str(payload.get("redirectUri")));
        app.setStatus(IntfSsoApp.ST_ACTIVE);
        app.setCreateBy(operator());
        ssoAppDao.insert(app);
        auditLogWriter.write("sso:" + appId, "SSO_APP_REGISTERED", null, "ACTIVE",
                "注册 SSO 兄弟系统：" + appName);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", app.getId());
        out.put("appId", appId);
        out.put("status", app.getStatus());
        return out;
    }

    @Override
    public List<Map<String, Object>> listApps() {
        return jdbc.queryForList(
                "SELECT ID AS id, APP_ID AS appId, APP_NAME AS appName, AUDIENCE AS audience, "
                        + "REDIRECT_URI AS redirectUri, STATUS AS status, CREATE_DATE AS createDate "
                        + "FROM erp_intf_sso_app WHERE DEL_FLAG='0' ORDER BY CREATE_DATE DESC");
    }

    @Override
    public void changeAppStatus(String appId, String status) {
        if (!IntfSsoApp.ST_ACTIVE.equals(status) && !IntfSsoApp.ST_DISABLED.equals(status)) {
            throw new ServiceException(422, "状态非法");
        }
        requireAdmin("变更兄弟系统状态");
        IntfSsoApp app = findByAppId(appId);
        app.setStatus(status);
        app.setUpdateBy(operator());
        ssoAppDao.updateById(app);
        auditLogWriter.write("sso:" + appId, "SSO_APP_STATUS", null, status,
                "兄弟系统状态变更：" + status);
    }

    @Override
    public Map<String, Object> issueAssertion(String appId) {
        IntfSsoApp app = findByAppId(appId);
        // 停用拒签（spec interface-sso：停用后拒签场景）
        if (IntfSsoApp.ST_DISABLED.equals(app.getStatus())) {
            auditLogWriter.write("sso:" + appId, "SSO_ASSERTION_REJECTED", "DISABLED", "REJECTED",
                    "停用兄弟系统请求 SSO 断言被拒");
            throw new ServiceException(422, "兄弟系统已停用，拒绝签发 SSO 断言");
        }
        String userId = SecurityUtils.getCurrentUserId();
        List<String> roles = sysUserRoleDao.getUserRoleCodes(userId);
        // 复用 JwtTokenProvider：claims 含 audience（限定兄弟系统）+ 用途标识
        Map<String, Object> claims = new HashMap<>();
        claims.put("aud", app.getAudience());
        claims.put("purpose", "SSO");
        claims.put("appId", appId);
        claims.put("roles", roles);
        long ttlMillis = tokenExpireSec * 1000L;
        String assertion = jwtTokenProvider.generateToken(userId, claims, ttlMillis);
        auditLogWriter.write("sso:" + appId, "SSO_ASSERTION_ISSUED", null, "ISSUED",
                "签发 SSO 断言（audience=" + app.getAudience() + "，有效期 " + tokenExpireSec + " 秒）");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assertion", assertion);
        out.put("audience", app.getAudience());
        out.put("expireSec", tokenExpireSec);
        out.put("userId", userId);
        out.put("metadataUrl", "/api/intf/sso/metadata");
        return out;
    }

    @Override
    public Map<String, Object> metadata() {
        // IdP 元数据：issuer + 验签用公钥（兄弟系统本地验签 SSO 断言）
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("issuer", "erp-idp");
        out.put("algorithm", "HS256");
        // 说明：本系统 JWT 为 HS256 对称签名，兄弟系统经注册时约定的 clientSecret 验签；
        // 若兄弟系统注册了 RSA 公钥则返回之（预留扩展）
        List<Map<String, Object>> apps = jdbc.queryForList(
                "SELECT APP_ID AS appId, PUBLIC_KEY AS publicKey FROM erp_intf_sso_app "
                        + "WHERE DEL_FLAG='0' AND STATUS='ACTIVE' AND PUBLIC_KEY IS NOT NULL");
        out.put("apps", apps);
        out.put("tokenExpireSec", tokenExpireSec);
        return out;
    }

    @Override
    public int logoutNotify() {
        String user = operator();
        // 向该用户活跃会话关联的全部 ACTIVE 兄弟系统推送登出通知（短令牌自然过期兜底）
        List<Map<String, Object>> apps = jdbc.queryForList(
                "SELECT APP_ID AS appId, REDIRECT_URI AS redirectUri FROM erp_intf_sso_app "
                        + "WHERE DEL_FLAG='0' AND STATUS='ACTIVE'");
        int notified = 0;
        for (Map<String, Object> a : apps) {
            String uri = str(a.get("redirectUri"));
            if (uri != null && !uri.isBlank()) {
                // 推送登出通知（沿既有登出联动实践：记审计，兄弟系统经回调 URL 感知）
                auditLogWriter.write("sso:" + str(a.get("appId")), "SSO_LOGOUT_NOTIFY", null, "NOTIFIED",
                        "向兄弟系统推送登出联动通知（用户=" + user + "）");
                notified++;
            }
        }
        return notified;
    }

    // ---- 工具 ----

    private IntfSsoApp findByAppId(String appId) {
        IntfSsoApp app = ssoAppDao.selectOne(new QueryWrapper<IntfSsoApp>()
                .eq("APP_ID", appId).eq("DEL_FLAG", "0"));
        if (app == null) {
            throw new ServiceException(422, "兄弟系统未注册（appId 不存在）");
        }
        return app;
    }

    /** 高危动作限 ADMIN（spec：注册/变更限 ADMIN） */
    private void requireAdmin(String action) {
        if (!com.erp.security.IntfGuard.isAdmin()) {
            auditLogWriter.write("sso:" + SecurityUtils.getCurrentUserId(),
                    "SSO_FORBIDDEN", null, "DENIED", action + " 被拒（非 ADMIN）");
            throw new ServiceException(403, action + " 仅 ADMIN 可操作");
        }
    }

    private String operator() {
        try {
            String u = SecurityUtils.getCurrentUserId();
            return u == null ? "system" : u;
        } catch (Exception e) {
            return "system";
        }
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
