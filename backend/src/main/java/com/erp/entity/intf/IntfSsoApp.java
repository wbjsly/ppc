package com.erp.entity.intf;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * SSO 兄弟系统 IdP 注册（add-intf-portal-menus，spec interface-sso）。
 * 本系统作 IdP：ERP 向兄弟系统（OA/CRM）签发限定 audience 的 SSO 断言（复用 JwtTokenProvider）。
 * APP_ID 唯一；停用（DISABLED）拒签。
 */
@Getter
@Setter
@TableName("erp_intf_sso_app")
public class IntfSsoApp extends BaseEntity {

    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_DISABLED = "DISABLED";

    private String appId;
    private String appName;
    private String publicKey;
    private String clientSecret;
    private String audience;
    private String redirectUri;
    private String status;
}
