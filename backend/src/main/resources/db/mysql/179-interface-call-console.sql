-- ============================================================
-- 179-interface-call-console.sql
-- change: add-interface-call-console（M12.3 接口调用操作台）
--   ① erp_intf_tier_change_log 档位变更历史表（design D1：
--     自动降档现仅写 channel.downgradeFrom/At 最后一次、手动调档零留痕，
--     spec 要求历史可查且不可改删 → 只 INSERT 的历史表）
--   ② erp_intf_call_log 补三查询索引（design D4：Trace 下钻 REQ_ID、
--     时间窗聚合 CALL_AT、按调用方聚合 CALLER+CALL_AT；现状仅主键）
-- ============================================================
-- 幂等：CREATE TABLE IF NOT EXISTS + information_schema 判断索引 PREPARE（沿 058/166 惯例）
-- ============================================================

-- ---------- 1. 档位变更历史表（只 INSERT，服务层不提供 UPDATE/DELETE） ----------

CREATE TABLE IF NOT EXISTS erp_intf_tier_change_log (
    ID             VARCHAR(64)  NOT NULL,
    CALLER         VARCHAR(64)  NOT NULL COMMENT '调用方（API Key 或伙伴编码）',
    PARTNER_CODE   VARCHAR(64)  DEFAULT NULL,
    CHANGE_TYPE    VARCHAR(16)  NOT NULL COMMENT 'AUTO_DOWNGRADE=BR-4.9-19 自动降档 / MANUAL=手动调档',
    TIER_FROM      VARCHAR(16)  NOT NULL COMMENT '变更前档位 STRATEGIC/NORMAL/NEW/LOWEST',
    TIER_TO        VARCHAR(16)  NOT NULL COMMENT '变更后档位',
    LIMIT_FROM     INT          DEFAULT NULL COMMENT '变更前限值（次/分钟）',
    LIMIT_TO       INT          DEFAULT NULL COMMENT '变更后限值（次/分钟）',
    TRIGGER_REASON VARCHAR(255) DEFAULT NULL COMMENT '自动降档固定文案 / 手动操作备注',
    OPERATOR       VARCHAR(64)  NOT NULL COMMENT '自动=SYSTEM / 手动=操作者用户 ID',
    CREATE_BY      VARCHAR(64)  DEFAULT NULL,
    CREATE_DATE    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY      VARCHAR(64)  DEFAULT NULL,
    UPDATE_DATE    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG       VARCHAR(1)   NOT NULL DEFAULT '0',
    VER_NO         INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_TIER_CHG_CALLER (CALLER, CREATE_DATE),
    KEY IDX_TIER_CHG_AT (CREATE_DATE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='限流档位变更历史（自动降档+手动调档同源留痕，spec 12.3.2 不可改删）';

-- ---------- 2. erp_intf_call_log 查询索引（Trace / 时间窗 / 调用方聚合） ----------

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'erp_intf_call_log'
                   AND INDEX_NAME = 'IDX_CALL_LOG_REQ');
SET @ddl := IF(@has_idx = 0,
    'ALTER TABLE erp_intf_call_log ADD INDEX IDX_CALL_LOG_REQ (REQ_ID)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'erp_intf_call_log'
                   AND INDEX_NAME = 'IDX_CALL_LOG_AT');
SET @ddl := IF(@has_idx = 0,
    'ALTER TABLE erp_intf_call_log ADD INDEX IDX_CALL_LOG_AT (CALL_AT)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'erp_intf_call_log'
                   AND INDEX_NAME = 'IDX_CALL_LOG_CALLER_AT');
SET @ddl := IF(@has_idx = 0,
    'ALTER TABLE erp_intf_call_log ADD INDEX IDX_CALL_LOG_CALLER_AT (CALLER, CALL_AT)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
