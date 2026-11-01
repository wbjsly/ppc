-- 200-adhoc-query-console.sql（add-adhoc-query-console，spec bi-adhoc-query / bi-temp-report）
-- 幂等：可重复执行。
-- ① erp_bi_adhoc_task / erp_bi_temp_report / erp_bi_saved_query 三新表；
-- ② 系统参数三枚（ADHOC_DAILY_QUOTA / ADHOC_QUERY_TIMEOUT / EXPORT_ROW_LIMIT）；
-- ③ 菜单 14.6.1~3 权限。

-- ---- ① 三新表 ----
CREATE TABLE IF NOT EXISTS erp_bi_adhoc_task (
    ID            varchar(64)  NOT NULL,
    TASK_NO       varchar(32)  NOT NULL COMMENT '任务号 ADH+时间戳',
    USER_NAME     varchar(64)  NOT NULL COMMENT '任务属主（执行时行级权限身份）',
    DEPT_NAME     varchar(128) NULL COMMENT '部门（用量统计维度）',
    TEMPLATE_ID   varchar(64)  NOT NULL COMMENT '数据集=模板ID',
    QUERY_JSON    text         NOT NULL COMMENT '结构化查询定义快照',
    STATUS        varchar(16)  NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED/RUNNING/DONE/FAILED/TIMEOUT/REJECTED',
    PRIORITY      varchar(8)   NOT NULL DEFAULT 'NORMAL' COMMENT 'SMALL/NORMAL/LOW',
    EST_ROWS      bigint       NULL COMMENT '预估扫描行数',
    EST_MS        bigint       NULL COMMENT '预估耗时毫秒',
    ACTUAL_ROWS   bigint       NULL COMMENT '实际返回行数',
    EXEC_MS       bigint       NULL COMMENT '实际执行毫秒',
    RESULT_JSON   longtext     NULL COMMENT '结果集（≤EXPORT_ROW_LIMIT 行；同步路径为空）',
    TRUNCATED     char(1)      NOT NULL DEFAULT '0' COMMENT '结果截断标志',
    ERROR_MSG     varchar(1000) NULL,
    NOTIFY_FLAG   char(1)      NOT NULL DEFAULT '0' COMMENT '完成通知已发',
    REJECT_REASON varchar(500) NULL COMMENT '预检阻断原因',
    CREATE_BY     varchar(64)  NULL,
    CREATE_DATE   datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY     varchar(64)  NULL,
    UPDATE_DATE   datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG      varchar(1)   NOT NULL DEFAULT '0',
    VER_NO        int          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY uk_adhoc_task_no (TASK_NO),
    KEY idx_adhoc_user_date (USER_NAME, CREATE_DATE),
    KEY idx_adhoc_status (STATUS, PRIORITY)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='即席查询任务（含预检阻断留痕）';

CREATE TABLE IF NOT EXISTS erp_bi_temp_report (
    ID              varchar(64)  NOT NULL,
    CODE            varchar(64)  NOT NULL COMMENT '临时报表编码 TMP+时间戳',
    NAME            varchar(200) NOT NULL,
    REMARK          varchar(500) NULL COMMENT '说明',
    TEMPLATE_ID     varchar(64)  NOT NULL COMMENT '数据集=模板ID',
    QUERY_JSON      text         NOT NULL COMMENT '查询定义快照（打开时重跑）',
    SHARE_JSON      text         NULL COMMENT '共享清单 [{type:user|role,code}]',
    EXPIRE_AT       datetime     NOT NULL COMMENT '创建+30天',
    EXPIRE_NOTIFIED char(1)      NOT NULL DEFAULT '0' COMMENT '到期前3天提醒已发',
    ARCHIVED_FLAG   char(1)      NOT NULL DEFAULT '0' COMMENT '到期归档',
    PROMOTED_TO     varchar(64)  NULL COMMENT '转正后模板ID',
    PROMOTE_AT      datetime     NULL,
    CREATE_BY       varchar(64)  NULL,
    CREATE_DATE     datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY       varchar(64)  NULL,
    UPDATE_DATE     datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG        varchar(1)   NOT NULL DEFAULT '0',
    VER_NO          int          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY uk_tmp_rpt_code (CODE),
    KEY idx_tmp_rpt_creator (CREATE_BY, ARCHIVED_FLAG),
    KEY idx_tmp_rpt_expire (EXPIRE_AT, ARCHIVED_FLAG)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='临时报表（查询定义+30天生命周期）';

CREATE TABLE IF NOT EXISTS erp_bi_saved_query (
    ID          varchar(64)  NOT NULL,
    NAME        varchar(200) NOT NULL COMMENT '我的查询名称',
    USER_NAME   varchar(64)  NOT NULL COMMENT '仅本人可见',
    TEMPLATE_ID varchar(64)  NOT NULL,
    QUERY_JSON  text         NOT NULL,
    CREATE_BY   varchar(64)  NULL,
    CREATE_DATE datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY   varchar(64)  NULL,
    UPDATE_DATE datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG    varchar(1)   NOT NULL DEFAULT '0',
    VER_NO      int          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY idx_saved_q_user (USER_NAME, CREATE_DATE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='我的查询（即席查询定义保存复用）';

-- ---- ② 系统参数三枚 ----
INSERT IGNORE INTO erp_sys_param (ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
VALUES ('prm-adhoc-quota-200', 'ADHOC_DAILY_QUOTA', '50', 'NUMBER', 'BI', '超过后当日强制转后台（BR-4.10-33）', 'migration-200', NOW(), '0', 0),
       ('prm-adhoc-timeout-200', 'ADHOC_QUERY_TIMEOUT', '120', 'NUMBER', 'BI', '沙箱执行超时终止秒数（BR-4.10-32）', 'migration-200', NOW(), '0', 0),
       ('prm-export-rowlimit-200', 'EXPORT_ROW_LIMIT', '10000', 'NUMBER', 'BI', '单次查询/导出最大行数（预估超限转后台口径）', 'migration-200', NOW(), '0', 0);

-- ---- ③ 菜单 14.6.1~3 权限 ----
-- 14.6.1 即席查询 / 14.6.3 临时报表：登录即可（SecurityConfig authenticated 控制，PERM 留空）
-- 14.6.2 查询治理：BI_DESIGNER/ADMIN
UPDATE erp_admin_menu SET PERM = 'ROLE_BI_DESIGNER,ROLE_ADMIN'
WHERE MENU_CODE = '14.6.2' AND (PERM IS NULL OR PERM = '' OR PERM <> 'ROLE_BI_DESIGNER,ROLE_ADMIN');
UPDATE erp_admin_menu SET PERM = ''
WHERE MENU_CODE IN ('14.6.1', '14.6.3') AND PERM IS NULL;
