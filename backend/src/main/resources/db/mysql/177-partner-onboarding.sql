-- ============================================================
-- 迁移 177：SCM 门户入驻（add-partner-onboarding，M12.1 三叶）
--   ① erp_scm_partner_apply 引入申请单 + 证照子表 + 激活 token 三表
--   ② ROLE_COMPLIANCE 角色
--   ③ M12-1 三叶菜单（自包含 INSERT IGNORE，099 重生成前迁移即可用）
--   ④ 字典 MATERIAL_GROUP_CRITICAL（关键物料组白名单，可空种子）
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE + UPDATE 只补空
-- ============================================================

-- ---------- 1. 引入申请单（FR-4.9-1-1 流程载体） ----------

CREATE TABLE IF NOT EXISTS erp_scm_partner_apply (
    ID                 VARCHAR(64)  NOT NULL,
    APPLY_NO           VARCHAR(32)  NOT NULL COMMENT 'PA+yyyyMMdd+流水',
    PARTNER_TYPE       VARCHAR(16)  NOT NULL DEFAULT 'SUPPLIER' COMMENT 'SUPPLIER 本期|CUSTOMER 预留',
    SUPPLIER_NAME      VARCHAR(128) NOT NULL,
    TAX_NO             VARCHAR(18)  NOT NULL COMMENT '统一社会信用代码（18位 L1 校验）',
    CONTACT_NAME       VARCHAR(64)  NOT NULL,
    CONTACT_PHONE      VARCHAR(32)  NOT NULL,
    CONTACT_EMAIL      VARCHAR(128) NOT NULL COMMENT '激活链接送达邮箱',
    MAIN_CATEGORY      VARCHAR(128) NULL COMMENT '主营品类',
    EST_ANNUAL_AMOUNT  DECIMAL(15,2) NULL COMMENT '预估年采购额（风险判定输入）',
    KEY_MATERIAL_FLAG  CHAR(1)      NOT NULL DEFAULT '0' COMMENT '关键物料判定结果快照 1|0',
    RISK_LEVEL         VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' COMMENT 'HIGH 三级|NORMAL 两级（判定落值防漂移）',
    BANK_NAME          VARCHAR(128) NULL,
    PAYMENT_TERMS      VARCHAR(64)  NULL COMMENT '付款条件（建档必填）',
    STATUS             VARCHAR(16)  NOT NULL DEFAULT 'DRAFT'
                     COMMENT 'DRAFT|APPROVING|APPROVED|REJECTED|CLOSED',
    REJECT_COUNT       INT          NOT NULL DEFAULT 0 COMMENT '驳回累计（≥3 自动关闭 BR-4.9-10）',
    SUPPLIER_ID        VARCHAR(64)  NULL COMMENT '审核通过自动建档回写',
    ACCOUNT_ID         VARCHAR(64)  NULL COMMENT '待激活门户账号回写',
    APPROVAL_ID        VARCHAR(64)  NULL,
    CLOSE_REASON       VARCHAR(512) NULL,
    REMARK             VARCHAR(512) NULL,
    CREATE_BY          VARCHAR(64)  NULL,
    CREATE_DATE        DATETIME     NULL,
    UPDATE_BY          VARCHAR(64)  NULL,
    UPDATE_DATE        DATETIME     NULL,
    DEL_FLAG           CHAR(1)      NOT NULL DEFAULT '0',
    VER_NO             BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_SCM_PA_NO (APPLY_NO),
    UNIQUE KEY UK_SCM_PA_TAX (TAX_NO, PARTNER_TYPE) COMMENT '信用代码+类型唯一（BR-4.9-08 重复阻断）',
    KEY IDX_SCM_PA_STATUS (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='伙伴引入申请单（add-partner-onboarding，FR-4.9-1-1）';

-- ---------- 2. 申请单证照子表（FR-4.9-1-2 文件挂附件） ----------

CREATE TABLE IF NOT EXISTS erp_scm_partner_apply_cert (
    ID               VARCHAR(64)  NOT NULL,
    APPLY_ID         VARCHAR(64)  NOT NULL,
    CERT_TYPE        VARCHAR(32)  NOT NULL
                   COMMENT 'LICENSE 营业执照|QMS 质量体系|ENV 环保|PERMIT 行业许可|OTHER',
    CERT_NO          VARCHAR(64)  NULL,
    ISSUE_DATE       DATE         NULL,
    EXPIRE_DATE      DATE         NOT NULL COMMENT '有效期（< 当天 BR-4.9-09 阻断）',
    ATTACH_COUNT     INT          NOT NULL DEFAULT 0 COMMENT '附件数冗余（每行必传≥1）',
    OCR_STATUS       VARCHAR(16)  NULL COMMENT '预留：本期恒空（OCR 不做）',
    OCR_EXTRACT_JSON TEXT         NULL COMMENT '预留：本期恒空',
    CREATE_BY        VARCHAR(64)  NULL,
    CREATE_DATE      DATETIME     NULL,
    UPDATE_BY        VARCHAR(64)  NULL,
    UPDATE_DATE      DATETIME     NULL,
    DEL_FLAG         CHAR(1)      NOT NULL DEFAULT '0',
    VER_NO           BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_SCM_PAC_APPLY (APPLY_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='伙伴引入申请证照（add-partner-onboarding，FR-4.9-1-2）';

-- ---------- 3. 门户账号激活 token（FR-4.9-1-4 / BR-4.9-11） ----------

CREATE TABLE IF NOT EXISTS erp_scm_partner_activate_token (
    ID            VARCHAR(64)  NOT NULL,
    ACCOUNT_ID    VARCHAR(64)  NOT NULL COMMENT '待激活门户账号',
    SUPPLIER_ID   VARCHAR(64)  NOT NULL,
    TOKEN         VARCHAR(128) NOT NULL COMMENT '一次性随机 64hex',
    EXPIRE_AT     DATETIME     NOT NULL COMMENT '生成 +7 天',
    USED_AT       DATETIME     NULL COMMENT '激活时间（一次性）',
    INVALID_FLAG  CHAR(1)      NOT NULL DEFAULT '0' COMMENT '1=已失效（过期扫描/重发旧链）',
    CREATE_BY     VARCHAR(64)  NULL,
    CREATE_DATE   DATETIME     NULL,
    UPDATE_BY     VARCHAR(64)  NULL,
    UPDATE_DATE   DATETIME     NULL,
    DEL_FLAG      CHAR(1)      NOT NULL DEFAULT '0',
    VER_NO        BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_SCM_PAT_TOKEN (TOKEN),
    KEY IDX_SCM_PAT_ACCOUNT (ACCOUNT_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门户账号激活 token（add-partner-onboarding，BR-4.9-11）';

-- ---------- 4. 角色种子（合规终审） ----------

INSERT IGNORE INTO erp_admin_role (ID, ROLE_CODE, ROLE_NAME, SORT_ORDER, STATUS, CREATE_BY, DEL_FLAG, VER_NO)
VALUES ('role-compliance', 'ROLE_COMPLIANCE', '合规', 32, '1', 'system', '0', 0);

-- ---------- 5. M12-1 三叶菜单（自包含 INSERT IGNORE；099 重生成后同 ID 幂等） ----------

INSERT IGNORE INTO erp_admin_menu (ID, PARENT_ID, MENU_CODE, TITLE, PATH, ICON, SORT_ORDER, PERM, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
VALUES ('M12',     '',        '12',     '外部协同',   '',        '',   '120', '',                                          '1', 'system', NOW(), '0', 0),
       ('M12-1',   'M12',     '12.1',   '门户入驻',  '',        '',   '10',  '',                                          '1', 'system', NOW(), '0', 0),
       ('M12-1-1', 'M12-1',   '12.1.1', '伙伴注册',  '/m/12.1.1', '', '10',  'ROLE_PURCHASER,ROLE_ADMIN',               '1', 'system', NOW(), '0', 0),
       ('M12-1-2', 'M12-1',   '12.1.2', '资质审核',  '/m/12.1.2', '', '20',  'ROLE_PURCHASER,ROLE_QUALITY_MGR,ROLE_COMPLIANCE,ROLE_ADMIN', '1', 'system', NOW(), '0', 0),
       ('M12-1-3', 'M12-1',   '12.1.3', '通道开通',  '/m/12.1.3', '', '30',  'ROLE_INTF_OPS,ROLE_ADMIN',                  '1', 'system', NOW(), '0', 0);

-- 幂等补空（与 099 重生成结果冲突时以后者为准，此处只补缺）
UPDATE erp_admin_menu SET PERM = 'ROLE_PURCHASER,ROLE_ADMIN'
 WHERE ID = 'M12-1-1' AND (PERM IS NULL OR PERM = '');
UPDATE erp_admin_menu SET PERM = 'ROLE_PURCHASER,ROLE_QUALITY_MGR,ROLE_COMPLIANCE,ROLE_ADMIN'
 WHERE ID = 'M12-1-2' AND (PERM IS NULL OR PERM = '');
UPDATE erp_admin_menu SET PERM = 'ROLE_INTF_OPS,ROLE_ADMIN'
 WHERE ID = 'M12-1-3' AND (PERM IS NULL OR PERM = '');

-- ---------- 6. 关键物料组白名单字典（风险判定输入，种子可空） ----------

CREATE TABLE IF NOT EXISTS erp_scm_critical_group (
    ID             VARCHAR(64) NOT NULL,
    MATERIAL_GROUP VARCHAR(64) NOT NULL COMMENT '物料组编码（erp_mdm_item.MATERIAL_GROUP）',
    REMARK         VARCHAR(256) NULL,
    CREATE_BY      VARCHAR(64) NULL,
    CREATE_DATE    DATETIME    NULL,
    UPDATE_BY      VARCHAR(64) NULL,
    UPDATE_DATE    DATETIME    NULL,
    DEL_FLAG       CHAR(1)     NOT NULL DEFAULT '0',
    VER_NO         BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_SCM_CG_GROUP (MATERIAL_GROUP)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关键物料组白名单（add-partner-onboarding，BR-4.9-10 判定输入）';
