-- 198-perm-control-console.sql（add-perm-control-console，spec bi-row-permission / bi-temp-grant）
-- 幂等：可重复执行。
-- ① erp_bi_perm_rule 行级三维度加列；② 规则版本快照表；③ 临时授权表；④ 种子规则 VER=1 快照；⑤ 菜单 14.4 权限。

-- ---- ① erp_bi_perm_rule 加列 ----
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_bi_perm_rule' AND COLUMN_NAME = 'SCOPE_EXPR');
SET @sql := IF(@c = 0, 'ALTER TABLE erp_bi_perm_rule ADD COLUMN SCOPE_EXPR varchar(16) NULL COMMENT ''SELF/SELF_SUBTREE/LIST（行级受控枚举）'' AFTER MASK_STYLE', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_bi_perm_rule' AND COLUMN_NAME = 'ORG_ID');
SET @sql := IF(@c = 0, 'ALTER TABLE erp_bi_perm_rule ADD COLUMN ORG_ID varchar(64) NULL COMMENT ''组织部门锚（SELF_SUBTREE 根 / LIST 逐条）'' AFTER SCOPE_EXPR', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_bi_perm_rule' AND COLUMN_NAME = 'SEC_LEVEL');
SET @sql := IF(@c = 0, 'ALTER TABLE erp_bi_perm_rule ADD COLUMN SEC_LEVEL varchar(16) NULL COMMENT ''数据密级上限（NULL=不限）'' AFTER ORG_ID', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_bi_perm_rule' AND COLUMN_NAME = 'SCOPE_LIST');
SET @sql := IF(@c = 0, 'ALTER TABLE erp_bi_perm_rule ADD COLUMN SCOPE_LIST text NULL COMMENT ''LIST 模式组织 ID 清单 JSON'' AFTER SEC_LEVEL', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---- ② 规则版本快照表（BR-4.10-17 回退锚）----
CREATE TABLE IF NOT EXISTS erp_bi_perm_rule_ver (
    ID            varchar(64)  NOT NULL,
    RULE_ID       varchar(64)  NOT NULL,
    RULE_VER      int          NOT NULL COMMENT '业务版本号（区别于 BaseEntity 乐观锁 VER_NO）',
    SNAPSHOT_JSON text         NOT NULL COMMENT '规则全字段快照（不可变）',
    EFF_DATE      datetime     NOT NULL,
    CREATE_BY     varchar(64)  NULL,
    CREATE_DATE   datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY     varchar(64)  NULL,
    UPDATE_DATE   datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG      varchar(1)   NOT NULL DEFAULT '0',
    VER_NO        int          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY idx_pv_rule (RULE_ID, RULE_VER)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='行级规则版本快照';

-- ---- ③ 临时授权表（FR-4.10-3-4 申请查看明文 ≤24h）----
CREATE TABLE IF NOT EXISTS erp_bi_temp_grant (
    ID          varchar(64)  NOT NULL,
    FIELD_KEY   varchar(128) NOT NULL COMMENT '申请明文的字段',
    USER_NAME   varchar(64)  NOT NULL,
    PURPOSE     varchar(500) NOT NULL COMMENT '用途说明',
    STATUS      varchar(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING_APPROVAL/ACTIVE/EXPIRED/REJECTED',
    APPLY_AT    datetime     NULL,
    EXPIRE_AT   datetime     NULL COMMENT '服务端钳制 ≤ APPLY_AT+24h',
    APPR_ID     varchar(64)  NULL,
    RECALL_AT   datetime     NULL COMMENT '实际收回时间',
    REMARK      varchar(500) NULL,
    CREATE_BY   varchar(64)  NULL,
    CREATE_DATE datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY   varchar(64)  NULL,
    UPDATE_DATE datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG    varchar(1)   NOT NULL DEFAULT '0',
    VER_NO      int          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY idx_tg_user (USER_NAME, STATUS),
    KEY idx_tg_field (FIELD_KEY, STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='敏感字段临时授权';

-- ---- ④ 存量种子规则 VER=1 快照初始化（幂等：只补没有快照的规则）----
INSERT INTO erp_bi_perm_rule_ver (ID, RULE_ID, RULE_VER, SNAPSHOT_JSON, EFF_DATE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
SELECT CONCAT('pv-init-', r.ID), r.ID, 1,
       JSON_OBJECT('ruleType', r.RULE_TYPE, 'targetKey', r.TARGET_KEY, 'userName', r.USER_NAME,
                   'entityId', r.ENTITY_ID, 'roleCode', r.ROLE_CODE, 'action', r.ACTION,
                   'maskStyle', r.MASK_STYLE, 'status', r.STATUS,
                   'scopeExpr', r.SCOPE_EXPR, 'orgId', r.ORG_ID, 'secLevel', r.SEC_LEVEL),
       NOW(), 'migration-198', NOW(), '0', 0
FROM erp_bi_perm_rule r
WHERE NOT EXISTS (SELECT 1 FROM erp_bi_perm_rule_ver v WHERE v.RULE_ID = r.ID);

-- ---- ⑤ 菜单 14.4.1~3 权限 ----
UPDATE erp_admin_menu SET PERM = 'ROLE_BI_DESIGNER,ROLE_ADMIN'
WHERE MENU_CODE IN ('14.4.1', '14.4.2', '14.4.3') AND (PERM IS NULL OR PERM = '' OR PERM <> 'ROLE_BI_DESIGNER,ROLE_ADMIN');
