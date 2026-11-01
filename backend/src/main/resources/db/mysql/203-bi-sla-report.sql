-- 203-bi-sla-report.sql（add-bi-sla-report，spec bi-sla-report）
-- 幂等：可重复执行。
-- ① erp_bi_sla_metric（五指标月度快照）/ erp_bi_sla_report（报告主体+五态状态机）两新表；
-- ② 菜单 14.9.1/14.9.2 权限 = ROLE_BI_DESIGNER,ROLE_ADMIN。
-- 与 16.9 sla-report-management（接口域，erp_sla_report）完全隔离。

-- ---- ① 两新表 ----
CREATE TABLE IF NOT EXISTS erp_bi_sla_metric (
    ID              varchar(64)  NOT NULL,
    PERIOD          varchar(7)   NOT NULL COMMENT 'yyyy-MM',
    METRIC_KEY      varchar(32)  NOT NULL COMMENT 'AVAILABILITY/QUERY_P95/REFRESH_ONTIME/EXPORT_SUCCESS/DAILY_REACH',
    METRIC_VALUE    decimal(18,4) NULL COMMENT '指标值（源数据不足=NULL+PARTIAL_FLAG）',
    TARGET          decimal(18,4) NULL COMMENT '目标值（QUERY_P95 为上限秒数，其余为下限百分比）',
    UNIT            varchar(16)  NULL COMMENT '%/s',
    PARTIAL_FLAG    varchar(1)   NOT NULL DEFAULT 'N' COMMENT 'Y=部分数据（BR-4.10-29 禁插值）',
    MISSING_DATES   varchar(500) NULL COMMENT '缺失日期清单（yyyy-MM-dd 逗号分隔）',
    ERROR_MSG       varchar(500) NULL,
    CREATE_BY       varchar(64)  NULL,
    CREATE_DATE     datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY       varchar(64)  NULL,
    UPDATE_DATE     datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG        varchar(1)   NOT NULL DEFAULT '0',
    VER_NO          int          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY uk_bi_sla_metric (PERIOD, METRIC_KEY)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报表SLA五指标月度快照';

CREATE TABLE IF NOT EXISTS erp_bi_sla_report (
    ID              varchar(64)  NOT NULL,
    PERIOD          varchar(7)   NOT NULL COMMENT 'yyyy-MM',
    VERSION         int          NOT NULL DEFAULT 1 COMMENT '归档后修改=新版本行，原行保留',
    METRICS_JSON    text         NULL COMMENT '五指标概览+三色判定（生成时固化引用快照）',
    ROOT_CAUSE_JSON text         NULL COMMENT '未达标三要素 [{metricKey,rootCause,owner,dueDate}]（BR-4.10-27）',
    IMPROVE_JSON    text         NULL COMMENT '改进措施+下月计划',
    STATUS          varchar(24)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING_APPROVAL/APPROVED/ARCHIVED/PUBLISHED',
    APPROVAL_ID     varchar(64)  NULL COMMENT '审批实例 ID（ApprovalEngine）',
    REJECT_REASON   varchar(500) NULL,
    ARCHIVE_BY      varchar(64)  NULL,
    ARCHIVE_AT      datetime     NULL,
    PUBLISH_BY      varchar(64)  NULL,
    PUBLISH_AT      datetime     NULL,
    PUBLISH_JSON    text         NULL COMMENT '发布记录 [{channel,at,operator,pdfRef}]',
    CREATE_BY       varchar(64)  NULL,
    CREATE_DATE     datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY       varchar(64)  NULL,
    UPDATE_DATE     datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG        varchar(1)   NOT NULL DEFAULT '0',
    VER_NO          int          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY uk_bi_sla_report_period (PERIOD, VERSION),
    KEY idx_bi_sla_report_status (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='月度报表SLA报告（4.10 流程五）';

-- ---- ② 菜单权限 ----
UPDATE erp_admin_menu SET PERM = 'ROLE_BI_DESIGNER,ROLE_ADMIN' WHERE MENU_CODE = '14.9.1';
UPDATE erp_admin_menu SET PERM = 'ROLE_BI_DESIGNER,ROLE_ADMIN' WHERE MENU_CODE = '14.9.2';
