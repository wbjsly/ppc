-- 201-kpi-dashboard.sql（add-kpi-dashboard，spec bi-kpi-dashboard）
-- 幂等：可重复执行。
-- ① erp_bi_kpi_def / erp_bi_kpi_target / erp_bi_kpi_snapshot / erp_bi_kpi_alert 四新表；
-- ② 系统参数三枚（KPI_ALERT_DAYS / KPI_SNAPSHOT_TIMEOUT / KPI_TREND_DAYS）；
-- ③ 菜单 14.7.1~3 权限留空（登录即可）；
-- ④ 六项内置 KPI 种子（METRIC_KEY 置空宽容降级：口径字典无对应已注册指标，注册后补挂）。

-- ---- ① 四新表 ----
CREATE TABLE IF NOT EXISTS erp_bi_kpi_def (
    ID                  varchar(64)  NOT NULL,
    KPI_KEY             varchar(64)  NOT NULL COMMENT 'KPI 编码唯一',
    NAME                varchar(200) NOT NULL,
    METRIC_KEY          varchar(64)  NULL COMMENT '口径引用（可空=未注册，宽容降级）',
    DIM_KEY             varchar(64)  NULL COMMENT '维度列（可空=仅全局值）',
    SQL_TEXT            text         NOT NULL COMMENT '取数 SQL（四列 kpi_key/dim_value/period_date/actual）',
    DRILL_TEMPLATE_ID   varchar(64)  NULL COMMENT '下钻模板（可空=PUBLISHED）',
    TARGET_MODE         varchar(16)  NOT NULL DEFAULT 'PERIODIC' COMMENT 'FIXED 固定值/PERIODIC 按期间',
    FIXED_TARGET        decimal(18,4) NULL COMMENT '固定目标（TARGET_MODE=FIXED 生效）',
    THRESHOLD_WARN      decimal(6,4) NOT NULL DEFAULT 0.8000 COMMENT '预警阈值（达成率）',
    THRESHOLD_CRITICAL  decimal(6,4) NOT NULL DEFAULT 0.5000 COMMENT '未达标阈值',
    STATUS              varchar(16)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
    OWNER               varchar(64)  NULL COMMENT '责任人（快照执行身份）',
    REMARK              varchar(500) NULL,
    CREATE_BY           varchar(64)  NULL,
    CREATE_DATE         datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY           varchar(64)  NULL,
    UPDATE_DATE         datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG            varchar(1)   NOT NULL DEFAULT '0',
    VER_NO              int          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY uk_kpi_def_key (KPI_KEY)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='经营看板 KPI 定义';

CREATE TABLE IF NOT EXISTS erp_bi_kpi_target (
    ID           varchar(64)  NOT NULL,
    KPI_KEY      varchar(64)  NOT NULL,
    PERIOD       varchar(7)   NOT NULL COMMENT 'yyyy-MM',
    TARGET_VALUE decimal(18,4) NOT NULL,
    CREATE_BY    varchar(64)  NULL,
    CREATE_DATE  datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY    varchar(64)  NULL,
    UPDATE_DATE  datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG     varchar(1)   NOT NULL DEFAULT '0',
    VER_NO       int          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY uk_kpi_target (KPI_KEY, PERIOD)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='KPI 期间目标';

CREATE TABLE IF NOT EXISTS erp_bi_kpi_snapshot (
    ID         varchar(64)  NOT NULL,
    KPI_KEY    varchar(64)  NOT NULL,
    DIM_VALUE  varchar(200) NOT NULL DEFAULT '' COMMENT '维度值（空串=全局）',
    SNAP_DATE  date         NOT NULL,
    ACTUAL     decimal(18,4) NULL,
    TARGET     decimal(18,4) NULL,
    RATIO      decimal(12,4) NULL COMMENT 'ACTUAL/TARGET（无目标 NULL）',
    STATUS     varchar(16)  NULL COMMENT 'ACHIEVED/WARN/BELOW（无目标 NULL）',
    ERROR_MSG  varchar(500) NULL COMMENT '取数失败留痕',
    CREATE_BY  varchar(64)  NULL,
    CREATE_DATE datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY  varchar(64)  NULL,
    UPDATE_DATE datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG   varchar(1)   NOT NULL DEFAULT '0',
    VER_NO     int          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY uk_kpi_snap (KPI_KEY, DIM_VALUE, SNAP_DATE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='KPI 每日快照';

CREATE TABLE IF NOT EXISTS erp_bi_kpi_alert (
    ID                varchar(64)  NOT NULL,
    KPI_KEY           varchar(64)  NOT NULL,
    DIM_VALUE         varchar(200) NOT NULL DEFAULT '' COMMENT '维度值（空串=全局）',
    STATUS            varchar(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/RECOVERED',
    DAYS_BREACHED     int          NOT NULL DEFAULT 0 COMMENT '连续低于目标天数',
    FIRST_BREACH_DATE date         NULL COMMENT '首次 breach 日',
    RECOVER_DATE      date         NULL COMMENT '恢复日',
    NOTIFY_FLAG       varchar(1)   NOT NULL DEFAULT '0' COMMENT '通知已发（防重）',
    CREATE_BY         varchar(64)  NULL,
    CREATE_DATE       datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY         varchar(64)  NULL,
    UPDATE_DATE       datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG          varchar(1)   NOT NULL DEFAULT '0',
    VER_NO            int          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY uk_kpi_alert (KPI_KEY, DIM_VALUE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='KPI 异常预警';

-- ---- ② 系统参数三枚 ----
INSERT IGNORE INTO erp_sys_param (ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
VALUES ('prm-kpi-alert-days-201', 'KPI_ALERT_DAYS', '3', 'NUMBER', 'BI', '连续 N 天低于目标触发预警（S-4.10-05）', 'migration-201', NOW(), '0', 0),
       ('prm-kpi-snap-timeout-201', 'KPI_SNAPSHOT_TIMEOUT', '120', 'NUMBER', 'BI', 'KPI 取数 SQL 超时秒数', 'migration-201', NOW(), '0', 0),
       ('prm-kpi-trend-days-201', 'KPI_TREND_DAYS', '30', 'NUMBER', 'BI', '趋势展示回看天数', 'migration-201', NOW(), '0', 0);

-- ---- ③ 菜单 14.7.1~3 权限（登录即可，PERM 留空） ----
UPDATE erp_admin_menu SET PERM = ''
WHERE MENU_CODE IN ('14.7.1', '14.7.2', '14.7.3') AND PERM IS NULL;

-- ---- ④ 六项内置 KPI 种子（METRIC_KEY 置空宽容降级） ----
-- 取数 SQL 契约：输出 kpi_key/dim_value/period_date/actual 四列；DIM_VALUE 空串=全局。
INSERT IGNORE INTO erp_bi_kpi_def (ID, KPI_KEY, NAME, METRIC_KEY, DIM_KEY, SQL_TEXT, TARGET_MODE, THRESHOLD_WARN, THRESHOLD_CRITICAL, STATUS, OWNER, REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
VALUES
('kpi-seed-sales-amt', 'KPI-SALES-AMT', '当月销售额', NULL, NULL,
 'SELECT ''KPI-SALES-AMT'' AS kpi_key, '''' AS dim_value, CURDATE() AS period_date, COALESCE(SUM(TOTAL_AMOUNT),0) AS actual FROM erp_sd_so WHERE DEL_FLAG=''0'' AND STATUS=''CONFIRMED'' AND CREATE_DATE >= DATE_FORMAT(NOW(),''%Y-%m-01'')',
 'PERIODIC', 0.8000, 0.5000, 'ENABLED', 'admin', '当月已确认订单销售总额', 'migration-201', NOW(), '0', 0),
('kpi-seed-ar-collect', 'KPI-AR-COLLECT', '回款率', NULL, NULL,
 'SELECT ''KPI-AR-COLLECT'' AS kpi_key, '''' AS dim_value, CURDATE() AS period_date, SUM(PAID_AMOUNT)/NULLIF(SUM(AMOUNT),0) AS actual FROM erp_fin_ar_invoice WHERE DEL_FLAG=''0''',
 'PERIODIC', 0.8000, 0.5000, 'ENABLED', 'admin', '已付金额/发票总额', 'migration-201', NOW(), '0', 0),
('kpi-seed-so-cust', 'KPI-SO-CUST', '客户销售贡献', NULL, 'CUSTOMER_NAME',
 'SELECT ''KPI-SO-CUST'' AS kpi_key, CUSTOMER_NAME AS dim_value, CURDATE() AS period_date, SUM(TOTAL_AMOUNT) AS actual FROM erp_sd_so WHERE DEL_FLAG=''0'' AND STATUS=''CONFIRMED'' AND CREATE_DATE >= DATE_FORMAT(NOW(),''%Y-%m-01'') AND CUSTOMER_NAME IS NOT NULL GROUP BY CUSTOMER_NAME',
 'PERIODIC', 0.8000, 0.5000, 'ENABLED', 'admin', '按客户维度当月销售贡献（前端取 TOP10）', 'migration-201', NOW(), '0', 0),
('kpi-seed-inv-qty', 'KPI-INV-QTY', '库存总量', NULL, NULL,
 'SELECT ''KPI-INV-QTY'' AS kpi_key, '''' AS dim_value, CURDATE() AS period_date, COALESCE(SUM(QTY),0) AS actual FROM erp_inv_stock WHERE DEL_FLAG=''0''',
 'PERIODIC', 0.8000, 0.5000, 'ENABLED', 'admin', '当前库存总量（件）', 'migration-201', NOW(), '0', 0),
('kpi-seed-ar-overdue', 'KPI-AR-OVERDUE', '逾期应收额', NULL, NULL,
 'SELECT ''KPI-AR-OVERDUE'' AS kpi_key, '''' AS dim_value, CURDATE() AS period_date, COALESCE(SUM(AMOUNT - PAID_AMOUNT),0) AS actual FROM erp_fin_ar_invoice WHERE DEL_FLAG=''0'' AND STATUS<>''PAID'' AND DUE_DATE < CURDATE()',
 'PERIODIC', 0.8000, 0.5000, 'ENABLED', 'admin', '逾期未付应收余额（越低越好，目标维护为上限）', 'migration-201', NOW(), '0', 0),
('kpi-seed-so-count', 'KPI-SO-COUNT', '当月订单数', NULL, NULL,
 'SELECT ''KPI-SO-COUNT'' AS kpi_key, '''' AS dim_value, CURDATE() AS period_date, COUNT(*) AS actual FROM erp_sd_so WHERE DEL_FLAG=''0'' AND STATUS=''CONFIRMED'' AND CREATE_DATE >= DATE_FORMAT(NOW(),''%Y-%m-01'')',
 'PERIODIC', 0.8000, 0.5000, 'ENABLED', 'admin', '当月已确认订单笔数', 'migration-201', NOW(), '0', 0);
