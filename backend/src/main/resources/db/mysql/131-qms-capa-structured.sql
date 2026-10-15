-- 131-qms-capa-structured.sql
-- rework-capa-evidence：CAPA 团队成员与经验沉淀结构化（design D4）
-- 幂等：information_schema 判存 + PREPARE（TEXT 列不带 DEFAULT 子句）

-- ---------- D1 团队成员清单（JSON 数组 [{name,domain,duty,leader}]） ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_qms_capa'
               AND COLUMN_NAME = 'TEAM_MEMBERS');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_qms_capa ADD COLUMN TEAM_MEMBERS TEXT NULL COMMENT ''8D 团队成员清单（JSON）'' AFTER OWNER_NAME',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- D8 经验沉淀物（JSON 数组 [{type,note,approvalId}]） ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_qms_capa'
               AND COLUMN_NAME = 'LEGACY_SINK');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_qms_capa ADD COLUMN LEGACY_SINK TEXT NULL COMMENT ''经验沉淀物（JSON，type∈FMEA/STANDARD/TRAINING/POKA_YOKE）'' AFTER ROOT_CAUSE_EVIDENCE',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
