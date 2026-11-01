-- 202-daily-report.sql（add-daily-report-subscription，spec bi-daily-report）
-- 幂等：可重复执行。
-- ① erp_bi_daily_report_def / erp_bi_daily_report_sub / erp_bi_report_push_log / erp_bi_report_receipt 四新表；
-- ② 系统参数三枚（REPORT_PUSH_RETRY_MAX / REPORT_READY_WAIT_MIN / REPORT_UNREAD_DAYS）；
-- ③ 菜单 14.8.1 权限留空（登录即可）、14.8.2 = BI_DESIGNER/ADMIN。

-- ---- ① 四新表 ----
CREATE TABLE IF NOT EXISTS erp_bi_daily_report_def (
    ID               varchar(64)  NOT NULL,
    CODE             varchar(64)  NOT NULL COMMENT '日报定义编码唯一',
    NAME             varchar(200) NOT NULL,
    METRIC_KEYS_JSON text         NOT NULL COMMENT '指标清单 [{"kpiKey":"...","title":"..."}]（引 erp_bi_kpi_def.KPI_KEY）',
    PUSH_TIME        varchar(5)   NOT NULL DEFAULT '07:30' COMMENT '推送时间 HH:mm（服务端强制不早于就绪）',
    STATUS           varchar(16)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
    REMARK           varchar(500) NULL,
    CREATE_BY        varchar(64)  NULL,
    CREATE_DATE      datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY        varchar(64)  NULL,
    UPDATE_DATE      datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG         varchar(1)   NOT NULL DEFAULT '0',
    VER_NO           int          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY uk_daily_report_code (CODE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='日报定义（指标清单+推送时间）';

CREATE TABLE IF NOT EXISTS erp_bi_daily_report_sub (
    ID             varchar(64) NOT NULL,
    REPORT_ID      varchar(64) NOT NULL COMMENT '日报定义 ID',
    USER_NAME      varchar(64) NOT NULL COMMENT '订阅人（接收人）',
    CHANNELS_JSON  varchar(500) NOT NULL DEFAULT '["NOTICE"]' COMMENT '通道清单 MAIL/WECOM/NOTICE',
    STATUS         varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED（退订置 DISABLED 留痕）',
    LAST_PUSH_DATE date        NULL COMMENT '最近推送日期',
    LAST_READ_AT   datetime    NULL COMMENT '最近阅读（回执汇总）',
    LAST_PROMPT_AT datetime    NULL COMMENT '最近 30 天未阅读 L4 提示时间（去重）',
    CREATE_BY      varchar(64) NULL,
    CREATE_DATE    datetime    NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY      varchar(64) NULL,
    UPDATE_DATE    datetime    NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG       varchar(1)  NOT NULL DEFAULT '0',
    VER_NO         int         NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY idx_daily_sub_report (REPORT_ID),
    KEY idx_daily_sub_user (USER_NAME)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='日报订阅关系';

CREATE TABLE IF NOT EXISTS erp_bi_report_push_log (
    ID           varchar(64) NOT NULL,
    SUB_ID       varchar(64) NOT NULL COMMENT '订阅 ID',
    REPORT_CODE  varchar(64) NOT NULL COMMENT '日报定义编码（冗余便于统计）',
    PUSH_DATE    date        NOT NULL COMMENT '推送日期（业务日=T-1 数据）',
    RECEIVER     varchar(64) NOT NULL COMMENT '接收人',
    CHANNEL      varchar(16) NOT NULL COMMENT 'MAIL/WECOM/NOTICE',
    SEND_STATUS  varchar(16) NOT NULL COMMENT 'SENT/FAILED/DEGRADED（DEGRADED=降级站内信送达）',
    RETRY_CNT    int         NOT NULL DEFAULT 0 COMMENT '重试次数',
    DEGRADED_FLAG varchar(1) NOT NULL DEFAULT 'N' COMMENT 'Y=双通道失败降级站内信',
    DATA_CUTOFF  date        NULL COMMENT '数据截止时间（快照 SNAP_DATE）',
    QUALITY_FLAG varchar(1)  NOT NULL DEFAULT 'N' COMMENT 'Y=数据质量异常（KPI ERROR_MSG）',
    PDF_REF      varchar(500) NULL COMMENT '日报 PDF 加密文件路径',
    ERROR_MSG    varchar(500) NULL,
    CREATE_BY    varchar(64) NULL,
    CREATE_DATE  datetime    NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY    varchar(64) NULL,
    UPDATE_DATE  datetime    NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG     varchar(1)  NOT NULL DEFAULT '0',
    VER_NO       int         NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY uk_push_log_sub_date_chan (SUB_ID, PUSH_DATE, CHANNEL),
    KEY idx_push_log_date (PUSH_DATE),
    KEY idx_push_log_receiver (RECEIVER)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='日报推送日志（逐通道）';

CREATE TABLE IF NOT EXISTS erp_bi_report_receipt (
    ID          varchar(64) NOT NULL,
    PUSH_LOG_ID varchar(64) NOT NULL COMMENT '推送日志 ID',
    OPEN_CNT    int         NOT NULL DEFAULT 0 COMMENT '打开次数累计',
    OPEN_AT     datetime    NULL COMMENT '最近打开时间',
    CREATE_BY   varchar(64) NULL,
    CREATE_DATE datetime    NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY   varchar(64) NULL,
    UPDATE_DATE datetime    NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG    varchar(1)  NOT NULL DEFAULT '0',
    VER_NO      int         NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY uk_report_receipt_log (PUSH_LOG_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='日报阅读回执（打开日报详情页写入）';

-- ---- ② 系统参数三枚 ----
INSERT INTO erp_sys_param (ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK)
SELECT UUID(), 'REPORT_PUSH_RETRY_MAX', '3', 'INT', 'BI',
       '日报推送单通道重试次数上限（邮件/企微独立重试）'
WHERE NOT EXISTS (SELECT 1 FROM erp_sys_param WHERE PARAM_KEY = 'REPORT_PUSH_RETRY_MAX');

INSERT INTO erp_sys_param (ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK)
SELECT UUID(), 'REPORT_READY_WAIT_MIN', '120', 'INT', 'BI',
       '日报数据就绪最长等待分钟数（超时通知+升级）'
WHERE NOT EXISTS (SELECT 1 FROM erp_sys_param WHERE PARAM_KEY = 'REPORT_READY_WAIT_MIN');

INSERT INTO erp_sys_param (ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK)
SELECT UUID(), 'REPORT_UNREAD_DAYS', '30', 'INT', 'BI',
       '日报订阅连续未阅读天数阈值（触发 L4 确认提示）'
WHERE NOT EXISTS (SELECT 1 FROM erp_sys_param WHERE PARAM_KEY = 'REPORT_UNREAD_DAYS');

-- ---- ③ 菜单权限：14.8.1 登录即可（留空）、14.8.2 BI_DESIGNER/ADMIN ----
UPDATE erp_admin_menu SET PERM = '' WHERE MENU_CODE = '14.8.1';
UPDATE erp_admin_menu SET PERM = 'ROLE_BI_DESIGNER,ROLE_ADMIN' WHERE MENU_CODE = '14.8.2';
