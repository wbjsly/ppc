-- 206: 运维 SLA 18 域（add-ops-sla）
-- 五新表：告警/值班/升级日志/容量指标/变更单 + incident 扩列 + 参数六枚
-- 菜单 18.1.1~18.5.2 已存在仅接路由；幂等：可重复执行

-- 1) 告警事件（18.2 一等公民：风暴聚合/P1 豁免/升级状态机载体）
CREATE TABLE IF NOT EXISTS erp_ops_alert (
    ID                varchar(64)  NOT NULL PRIMARY KEY,
    ALERT_NO          varchar(64)  NOT NULL,
    LEVEL             varchar(8)   NOT NULL COMMENT 'P1/P2/P3',
    SOURCE            varchar(64)  NOT NULL COMMENT 'sync-delay/capacity/change/manual',
    EVENT_TYPE        varchar(64)  NULL COMMENT '延迟来源事件类型',
    TITLE             varchar(200) NOT NULL,
    DETAIL            varchar(1000) NULL,
    METRIC_VALUE      decimal(18,4) NULL,
    THRESHOLD         decimal(18,4) NULL,
    ROOT_CAUSE_ID     varchar(64)  NULL COMMENT '风暴聚合根因告警ID',
    SUPPRESSED        varchar(1)   NOT NULL DEFAULT '0',
    SUPPRESS_WINDOW   varchar(64)  NULL,
    STATUS            varchar(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/CLAIMED/ESCALATED/RESOLVED/SUPPRESSED',
    ESCALATION_LEVEL  int          NOT NULL DEFAULT 0 COMMENT '0未升级/1L1/2L2/3L3',
    HANDLER           varchar(64)  NULL,
    INCIDENT_ID       varchar(64)  NULL,
    CREATE_BY         varchar(64)  NULL,
    CREATE_DATE       datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY         varchar(64)  NULL,
    UPDATE_DATE       datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG          varchar(1)   NOT NULL DEFAULT '0',
    VER_NO            int          NOT NULL DEFAULT 0,
    UNIQUE UK_ALERT_NO (ALERT_NO),
    KEY IDX_ALERT_STATUS (STATUS),
    KEY IDX_ALERT_LEVEL (LEVEL),
    KEY IDX_ALERT_TIME (CREATE_DATE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运维告警事件';

-- 2) 值班表（18.3）
CREATE TABLE IF NOT EXISTS erp_ops_oncall (
    ID          varchar(64) NOT NULL PRIMARY KEY,
    DUTY_DATE   date        NOT NULL,
    ON_LEVEL    varchar(8)  NOT NULL COMMENT 'L1/L2/L3',
    USER_NAME   varchar(64) NOT NULL,
    BACKUP_USER varchar(64) NULL,
    REMARK      varchar(200) NULL,
    CREATE_BY   varchar(64) NULL,
    CREATE_DATE datetime    NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY   varchar(64) NULL,
    UPDATE_DATE datetime    NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG    varchar(1)  NOT NULL DEFAULT '0',
    VER_NO      int         NOT NULL DEFAULT 0,
    UNIQUE UK_ONCALL (DUTY_DATE, ON_LEVEL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运维值班表';

-- 3) 升级日志（18.3）
CREATE TABLE IF NOT EXISTS erp_ops_escalation_log (
    ID             varchar(64) NOT NULL PRIMARY KEY,
    ALERT_ID       varchar(64) NULL,
    INCIDENT_ID    varchar(64) NULL,
    FROM_LEVEL     varchar(8)  NULL,
    TO_LEVEL       varchar(8)  NOT NULL,
    REASON         varchar(200) NULL,
    NOTIFY_CHANNEL varchar(64) NULL,
    ESCALATE_AT    datetime    NULL DEFAULT CURRENT_TIMESTAMP,
    CREATE_BY      varchar(64) NULL,
    CREATE_DATE    datetime    NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG       varchar(1)  NOT NULL DEFAULT '0',
    VER_NO         int         NOT NULL DEFAULT 0,
    KEY IDX_ESC_ALERT (ALERT_ID),
    KEY IDX_ESC_INCIDENT (INCIDENT_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='升级日志';

-- 4) 容量指标（18.4）
CREATE TABLE IF NOT EXISTS erp_ops_capacity_metric (
    ID           varchar(64)  NOT NULL PRIMARY KEY,
    METRIC_DATE  date         NOT NULL,
    METRIC_KEY   varchar(32)  NOT NULL COMMENT 'DB_SIZE/CONN_POOL/CPU',
    METRIC_VALUE decimal(18,4) NOT NULL,
    MAX_VALUE    decimal(18,4) NULL COMMENT '连接池最大值等',
    UNIT         varchar(16)  NULL,
    CREATE_BY    varchar(64)  NULL,
    CREATE_DATE  datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG     varchar(1)   NOT NULL DEFAULT '0',
    VER_NO       int          NOT NULL DEFAULT 0,
    UNIQUE UK_CAP (METRIC_DATE, METRIC_KEY),
    KEY IDX_CAP_KEY (METRIC_KEY, METRIC_DATE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运维容量指标日采集';

-- 5) 变更单（18.1）
CREATE TABLE IF NOT EXISTS erp_ops_change_ticket (
    ID                 varchar(64)  NOT NULL PRIMARY KEY,
    TICKET_NO          varchar(64)  NOT NULL,
    TITLE              varchar(200) NOT NULL,
    CHANGE_TYPE        varchar(32)  NOT NULL COMMENT 'NORMAL/EMERGENCY_FIX/GRAY',
    AFFECT_SCOPE       varchar(500) NULL,
    ESTIMATE_MIN       int          NOT NULL DEFAULT 60,
    ROLLBACK_MIN       int          NULL,
    ROLLBACK_PLAN_JSON longtext     NULL COMMENT '三件套：script/drillAt/drillValid/estimateMin',
    STATUS             varchar(20)  NOT NULL DEFAULT 'SUBMITTED'
        COMMENT 'SUBMITTED/APPROVED/EXECUTING/ROLLED_BACK/CLOSED',
    IN_WINDOW          varchar(1)   NULL,
    WINDOW_CHECK_NOTE  varchar(200) NULL,
    ACCOUNTING_GUARD   varchar(1)   NULL COMMENT '结账保障期标记',
    ROLLBACK_REASON    varchar(500) NULL,
    ROLLBACK_REF       varchar(64)  NULL,
    EXECUTOR           varchar(64)  NULL,
    GRAY_ERROR_RATE    decimal(8,4) NULL,
    CREATE_BY          varchar(64)  NULL,
    CREATE_DATE        datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY          varchar(64)  NULL,
    UPDATE_DATE        datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG           varchar(1)   NOT NULL DEFAULT '0',
    VER_NO             int          NOT NULL DEFAULT 0,
    UNIQUE UK_CHANGE_NO (TICKET_NO),
    KEY IDX_CHANGE_STATUS (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运维变更单';

-- 6) incident 扩列（复盘与改进项，全部可空/带默认零影响）
-- 幂等：列已存在则跳过（MySQL 8 无 ADD COLUMN IF NOT EXISTS，用存储过程判列）
DROP PROCEDURE IF EXISTS add_ops_col;
DELIMITER $$
CREATE PROCEDURE add_ops_col(IN tbl VARCHAR(64), IN col VARCHAR(64), IN def TEXT)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=tbl AND COLUMN_NAME=col) THEN
        SET @s = CONCAT('ALTER TABLE ', tbl, ' ADD COLUMN ', col, ' ', def);
        PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;
CALL add_ops_col('erp_ops_incident', 'REPORT_STATUS', "varchar(16) NULL COMMENT 'NOT_GENERATED/DONE'");
CALL add_ops_col('erp_ops_incident', 'REPORT_DUE_DATE', 'date NULL');
CALL add_ops_col('erp_ops_incident', 'ROOT_CAUSE', 'varchar(500) NULL');
CALL add_ops_col('erp_ops_incident', 'IMPROVE_ACTION', 'varchar(500) NULL');
CALL add_ops_col('erp_ops_incident', 'IMPROVE_OWNER', 'varchar(64) NULL');
CALL add_ops_col('erp_ops_incident', 'IMPROVE_DUE_DATE', 'date NULL');
CALL add_ops_col('erp_ops_incident', 'OVERDUE_ESCALATED', "varchar(1) NOT NULL DEFAULT '0'");
CALL add_ops_col('erp_ops_incident', 'ALERT_LEVEL', 'varchar(8) NULL');
CALL add_ops_col('erp_ops_incident', 'IS_IMPROVE', "varchar(1) NULL COMMENT '改进项标记'");
DROP PROCEDURE IF EXISTS add_ops_col;

-- 7) 参数六枚（幂等）
INSERT IGNORE INTO erp_sys_param (ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
SELECT UUID(), t.k, t.v, 'INT', 'OPS', t.r, 'migration-206', NOW(), '0', 0
FROM (SELECT 'P1_RESPONSE_MIN' k, '15' v, 'P1 认领时限分钟' r
      UNION SELECT 'ESCALATION_RESPONSE_MIN', '30', '层级响应时限分钟'
      UNION SELECT 'ALERT_STORM_THRESHOLD', '200', '告警风暴阈值条/分钟'
      UNION SELECT 'CAPACITY_UTIL_THRESHOLD', '80', '容量利用率阈值%'
      UNION SELECT 'SLA_SYNC_THRESHOLD', '5', '同步延迟阈值分钟'
      UNION SELECT 'DR_DRILL_INTERVAL_MONTHS', '6', '灾备演练间隔月（参数就位）') t
WHERE NOT EXISTS (SELECT 1 FROM erp_sys_param p WHERE p.PARAM_KEY=t.k AND p.DEL_FLAG='0');

-- 8) 运维 SLA 报告快照（追加：PARAM_VALUE varchar(128) 不足以存快照）
CREATE TABLE IF NOT EXISTS erp_ops_sla_report (
    ID           varchar(64) NOT NULL PRIMARY KEY,
    PERIOD       varchar(7)  NOT NULL COMMENT 'yyyy-MM',
    STATUS       varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ARCHIVED/PUBLISHED',
    METRICS_JSON longtext    NULL,
    PUBLISH_SCOPE varchar(64) NULL,
    CREATE_BY    varchar(64) NULL,
    CREATE_DATE  datetime    NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY    varchar(64) NULL,
    UPDATE_DATE  datetime    NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG     varchar(1)  NOT NULL DEFAULT '0',
    VER_NO       int         NOT NULL DEFAULT 0,
    UNIQUE UK_SLA_PERIOD (PERIOD)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运维 SLA 月度报告快照';
