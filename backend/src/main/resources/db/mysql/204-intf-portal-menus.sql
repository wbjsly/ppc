-- 204-intf-portal-menus.sql（add-intf-portal-menus，spec interface-console/interface-sso/interface-canary-release）
-- 幂等：可重复执行。
-- ① erp_intf_sso_app（兄弟系统 IdP 注册）/ erp_intf_gray_release（灰度规则）/
--    erp_intf_gray_step_log（灰度切量记录）三新表；
-- ② 菜单 15.1~15.8 PERM = ROLE_INTF_OPS,ROLE_ADMIN（沿 2.8.3）。

-- ---- ① 三新表 ----
CREATE TABLE IF NOT EXISTS erp_intf_sso_app (
    ID              varchar(64)  NOT NULL,
    APP_ID          varchar(64)  NOT NULL COMMENT '兄弟系统唯一标识（appId）',
    APP_NAME        varchar(128) NOT NULL COMMENT '兄弟系统名称（OA/CRM 等）',
    PUBLIC_KEY      varchar(2000) NULL COMMENT '验签公钥（RSA，供元数据端点返回）',
    CLIENT_SECRET   varchar(512) NULL COMMENT 'clientSecret（AES 加密存储，对齐 api-credential 先例）',
    AUDIENCE        varchar(128) NOT NULL COMMENT 'SSO 断言 audience 限定值',
    REDIRECT_URI    varchar(500) NULL COMMENT '登出联动回调 URL',
    STATUS          varchar(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED（停用拒签）',
    CREATE_BY       varchar(64)  NULL,
    CREATE_DATE     datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY       varchar(64)  NULL,
    UPDATE_DATE     datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG        varchar(1)   NOT NULL DEFAULT '0',
    VER_NO          int          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY uk_intf_sso_app (APP_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SSO 兄弟系统 IdP 注册（本系统作 IdP）';

CREATE TABLE IF NOT EXISTS erp_intf_gray_release (
    ID                  varchar(64)  NOT NULL,
    CHANNEL_CODE        varchar(64)  NOT NULL COMMENT '通道编码',
    OLD_CRED_ID         varchar(64)  NOT NULL COMMENT '旧凭证 ID（回滚目标）',
    NEW_CRED_ID         varchar(64)  NOT NULL COMMENT '新凭证 ID（灰度目标）',
    LADDER_JSON         varchar(200) NOT NULL COMMENT '流量百分比阶梯，如 [10,50,100]（SOP-5.5-B 步4）',
    CURRENT_STEP        int          NOT NULL DEFAULT 0 COMMENT '当前档位下标（0=首档 10%）',
    OBSERVE_MIN         int          NOT NULL DEFAULT 60 COMMENT '每档观察窗口（分钟）',
    ROLLBACK_ERROR_RATE decimal(6,2) NOT NULL DEFAULT 50.00 COMMENT '回滚错误率阈值%（沿 CIRCUIT_BREAK_ERROR_RATE 惯例）',
    STATUS              varchar(16)  NOT NULL DEFAULT 'DRAFT'
        COMMENT 'DRAFT / RUNNING / ROLLBACK / COMPLETED / ABORTED',
    CREATE_BY       varchar(64)  NULL,
    CREATE_DATE     datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY       varchar(64)  NULL,
    UPDATE_DATE     datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG        varchar(1)   NOT NULL DEFAULT '0',
    VER_NO          int          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY idx_intf_gray_channel (CHANNEL_CODE),
    KEY idx_intf_gray_status (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='凭证/版本灰度切流量规则';

CREATE TABLE IF NOT EXISTS erp_intf_gray_step_log (
    ID          varchar(64) NOT NULL,
    RELEASE_ID  varchar(64) NOT NULL COMMENT '灰度规则 ID',
    STEP        int         NOT NULL COMMENT '档位下标',
    PCT         int         NOT NULL COMMENT '流量百分比',
    ACTION      varchar(16) NOT NULL COMMENT 'STEP_IN（切量）/ ROLLBACK（回滚）/ ABORT（中止）',
    RESULT      varchar(16) NOT NULL COMMENT 'SUCCESS / FAILED',
    OPERATOR    varchar(64) NOT NULL COMMENT '操作人（自动回滚=system）',
    ERROR_RATE  decimal(6,2) NULL COMMENT '触发回滚时的错误率%',
    REMARK      varchar(500) NULL,
    CREATE_BY   varchar(64) NULL,
    CREATE_DATE datetime    NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY   varchar(64) NULL,
    UPDATE_DATE datetime    NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG    varchar(1)  NOT NULL DEFAULT '0',
    VER_NO      int         NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY idx_intf_gray_log_release (RELEASE_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='灰度切量/回滚记录';

-- ---- ② 15.x 菜单权限（沿 2.8.3）----
UPDATE erp_admin_menu SET PERM = 'ROLE_INTF_OPS,ROLE_ADMIN' WHERE MENU_CODE = '15.1';
UPDATE erp_admin_menu SET PERM = 'ROLE_INTF_OPS,ROLE_ADMIN' WHERE MENU_CODE = '15.2';
UPDATE erp_admin_menu SET PERM = 'ROLE_INTF_OPS,ROLE_ADMIN' WHERE MENU_CODE = '15.3';
UPDATE erp_admin_menu SET PERM = 'ROLE_INTF_OPS,ROLE_ADMIN' WHERE MENU_CODE = '15.4';
UPDATE erp_admin_menu SET PERM = 'ROLE_INTF_OPS,ROLE_ADMIN' WHERE MENU_CODE = '15.5';
UPDATE erp_admin_menu SET PERM = 'ROLE_INTF_OPS,ROLE_ADMIN' WHERE MENU_CODE = '15.6';
UPDATE erp_admin_menu SET PERM = 'ROLE_INTF_OPS,ROLE_ADMIN' WHERE MENU_CODE = '15.7';
UPDATE erp_admin_menu SET PERM = 'ROLE_INTF_OPS,ROLE_ADMIN' WHERE MENU_CODE = '15.8';
