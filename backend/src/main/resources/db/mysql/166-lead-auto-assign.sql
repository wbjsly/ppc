-- ============================================================
-- 166 线索自动分配（add-lead-auto-assign，spec crm-lead-management FR-4.8-1-3）
--   ①erp_crm_sales_profile 资深销售标记 ②erp_crm_assign_rule 分配规则
--   ③erp_crm_lead 加 REGION/INDUSTRY 选填列 ④DEMO 规则种子
-- ============================================================

-- ---------- 1. 资深销售标记 ----------

CREATE TABLE IF NOT EXISTS erp_crm_sales_profile (
    ID             VARCHAR(64) NOT NULL,
    EMP_ID         VARCHAR(64) NOT NULL COMMENT '销售登录账号（与 lead.OWNER_ID 同语义）',
    EMP_NAME       VARCHAR(64) DEFAULT NULL,
    IS_SENIOR      VARCHAR(1) NOT NULL DEFAULT '0' COMMENT '1=资深（A 级线索优先分配）',
    REMARK         VARCHAR(200) DEFAULT NULL,
    CREATE_BY      VARCHAR(64) DEFAULT NULL,
    CREATE_DATE    DATETIME DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY      VARCHAR(64) DEFAULT NULL,
    UPDATE_DATE    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG       VARCHAR(1) NOT NULL DEFAULT '0',
    VER_NO         INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_CRM_PROFILE_EMP (EMP_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售能力档案（本期仅资深标记，经理维护）';

-- ---------- 2. 分配规则 ----------

CREATE TABLE IF NOT EXISTS erp_crm_assign_rule (
    ID             VARCHAR(64) NOT NULL,
    GRADE          VARCHAR(2) NOT NULL COMMENT '适用等级 A/B/C',
    REGION         VARCHAR(64) DEFAULT NULL COMMENT '区域条件（空=通配）',
    INDUSTRY       VARCHAR(64) DEFAULT NULL COMMENT '行业条件（空=通配）',
    TARGET_EMP_IDS VARCHAR(1000) NOT NULL COMMENT '目标销售账号集（逗号分隔）',
    PRIORITY       INT NOT NULL DEFAULT 100 COMMENT '数字小者先匹配，首条命中生效',
    ENABLED        VARCHAR(1) NOT NULL DEFAULT '1',
    REMARK         VARCHAR(200) DEFAULT NULL,
    CREATE_BY      VARCHAR(64) DEFAULT NULL,
    CREATE_DATE    DATETIME DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY      VARCHAR(64) DEFAULT NULL,
    UPDATE_DATE    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG       VARCHAR(1) NOT NULL DEFAULT '0',
    VER_NO         INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_CRM_RULE_GRADE (GRADE, ENABLED, PRIORITY)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='线索自动分配规则（等级+区域/行业→目标销售集）';

-- ---------- 3. 线索加选填维度列（幂等） ----------

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_crm_lead' AND COLUMN_NAME = 'REGION');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE erp_crm_lead ADD COLUMN REGION VARCHAR(64) DEFAULT NULL COMMENT ''区域（选填，分配规则维度）'' AFTER SOURCE_CHANNEL',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_crm_lead' AND COLUMN_NAME = 'INDUSTRY');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE erp_crm_lead ADD COLUMN INDUSTRY VARCHAR(64) DEFAULT NULL COMMENT ''行业（选填，分配规则维度）'' AFTER REGION',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 4. DEMO 规则种子（演示值：B 级通配轮询组，经理可在 11.1.2 配置页调整） ----------

INSERT IGNORE INTO erp_crm_assign_rule
(ID, GRADE, REGION, INDUSTRY, TARGET_EMP_IDS, PRIORITY, ENABLED, REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
SELECT 'RULE-DEMO-B-ALL', 'B', NULL, NULL, 'sales1,sales2', 100, '1', 'DEMO 演示：B 级通配轮询组', 'migration', NOW(), '0', 0
WHERE NOT EXISTS (SELECT 1 FROM erp_crm_assign_rule WHERE ID = 'RULE-DEMO-B-ALL');
