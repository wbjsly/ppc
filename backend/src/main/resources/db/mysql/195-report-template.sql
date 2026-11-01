-- ============================================================
-- 195-report-template.sql
-- change: add-report-template-console（14.1 报表分析-模板管理，
--         spec report-template-management，C-4.10-01 / BR-4.10-07~11）
--   三表（design D1）：
--     erp_bi_report_template  模板主表（可执行元数据 + 状态机）
--     erp_bi_report_test      测试报告（三项结果 + SQL_HASH 作废锚）
--     erp_bi_report_version   版本快照（口径版本双维度绑定，只读）
--   三角色种子（design D7）：ROLE_BI_DESIGNER / ROLE_DATA_GOV / ROLE_BIZ_OWNER
--   菜单 14.1 四行权限幂等 UPDATE（ROLE_BI_DESIGNER,ROLE_ADMIN）
-- ============================================================
-- 幂等：CREATE TABLE IF NOT EXISTS / INSERT IGNORE / 受控 UPDATE，可重跑
-- ============================================================

CREATE TABLE IF NOT EXISTS erp_bi_report_template (
  ID                 VARCHAR(32)  NOT NULL COMMENT '主键',
  CODE               VARCHAR(64)  NOT NULL COMMENT '模板编码（唯一）',
  NAME               VARCHAR(128) NOT NULL COMMENT '模板名称',
  CATEGORY           VARCHAR(32)  NOT NULL DEFAULT 'STANDARD' COMMENT '分类：STANDARD 标准(≤10s) / COMPLEX 复杂(≤30s)——性能阈值档（D5）',
  SQL_TEXT           TEXT         NOT NULL COMMENT '只读查询 SQL（SELECT-only 白名单校验，D2）',
  COLUMNS_JSON       TEXT         DEFAULT NULL COMMENT '列定义 JSON（渲染表头/类型/敏感列掩码标记）',
  METRICS_JSON       TEXT         DEFAULT NULL COMMENT '指标引用数组 [{metricKey, formula}]（口径 diffCheck 对象，D3）',
  STATUS             VARCHAR(24)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态机 DRAFT/TESTING/PENDING_APPROVAL/PUBLISHED/REJECTED',
  ACTIVE_VERSION_ID  VARCHAR(32)  DEFAULT NULL COMMENT '当前生效版本快照 ID（草稿不生效线版，D1）',
  APPR_INSTANCE_ID   VARCHAR(64)  DEFAULT NULL COMMENT '发布审批实例（三方串行链）',
  REMARK             VARCHAR(500) DEFAULT NULL,
  CREATE_BY          VARCHAR(64)  DEFAULT NULL,
  CREATE_DATE        DATETIME     DEFAULT NULL,
  UPDATE_BY          VARCHAR(64)  DEFAULT NULL,
  UPDATE_DATE        DATETIME     DEFAULT NULL,
  DEL_FLAG           VARCHAR(1)   NOT NULL DEFAULT '0',
  VER_NO             INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (ID),
  UNIQUE KEY UK_BI_RPT_TPL_CODE (CODE),
  KEY IDX_BI_RPT_TPL_STATUS (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报表模板（14.1 模板设计/发布，spec report-template-management）';

CREATE TABLE IF NOT EXISTS erp_bi_report_test (
  ID              VARCHAR(32)  NOT NULL COMMENT '主键',
  TEMPLATE_ID     VARCHAR(32)  NOT NULL COMMENT '模板 ID',
  SQL_HASH        VARCHAR(16)  NOT NULL COMMENT 'SQL+列+指标 元数据 SHA-256 前16位（变更作废锚，D5）',
  PERF_MS         BIGINT       NOT NULL DEFAULT 0 COMMENT '响应时间毫秒',
  PERF_LEVEL      VARCHAR(16)  NOT NULL DEFAULT 'STANDARD' COMMENT '阈值档 STANDARD(10s)/COMPLEX(30s)',
  PERF_OK         VARCHAR(1)   NOT NULL DEFAULT '0' COMMENT '性能项 1 达标/0 超标（D5②）',
  ROW_COUNT       BIGINT       NOT NULL DEFAULT 0 COMMENT '返回行数（包 LIMIT 后实际）',
  SAMPLE_JSON     TEXT         DEFAULT NULL COMMENT '前10行样本（准确性人工核对，D5①）',
  PERM_ROW_COUNT  BIGINT       DEFAULT NULL COMMENT '行级权限过滤版行数（D5③）',
  PERM_OK         VARCHAR(1)   NOT NULL DEFAULT '0' COMMENT '权限项 1 符合预期/0 异常',
  ACCURACY_OK     VARCHAR(1)   NOT NULL DEFAULT '0' COMMENT '人工核对确认 1/0（confirmAccuracy 置位）',
  STATUS          VARCHAR(8)   NOT NULL DEFAULT 'FAIL' COMMENT '总体 PASS/FAIL（三项合取）',
  VALID           VARCHAR(1)   NOT NULL DEFAULT '1' COMMENT '有效标记：模板变更置 0 作废（强制重测）',
  EXEC_BY         VARCHAR(64)  DEFAULT NULL COMMENT '执行人',
  EXEC_AT         DATETIME     DEFAULT NULL COMMENT '执行时间',
  CREATE_BY       VARCHAR(64)  DEFAULT NULL,
  CREATE_DATE     DATETIME     DEFAULT NULL,
  DEL_FLAG        VARCHAR(1)   NOT NULL DEFAULT '0',
  VER_NO          INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (ID),
  KEY IDX_BI_RPT_TEST_TPL (TEMPLATE_ID, VALID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报表模板测试报告（14.1 模板测试，三项报告+强制重测）';

CREATE TABLE IF NOT EXISTS erp_bi_report_version (
  ID                  VARCHAR(32)  NOT NULL COMMENT '主键',
  TEMPLATE_ID         VARCHAR(32)  NOT NULL COMMENT '模板 ID',
  VERSION_NO          INT          NOT NULL COMMENT '模板内自增版本号（回退生成新号保留链，D1）',
  DEF_JSON            TEXT         NOT NULL COMMENT '全量定义快照（SQL/列/指标引用，发布即不可变）',
  METRIC_VERSIONS_JSON TEXT        DEFAULT NULL COMMENT '口径版本绑定 [{metricKey, metricVersion}]（BR-4.10-07 双维度）',
  ROLLBACK_FROM       INT          DEFAULT NULL COMMENT '回退来源版本号（溯源；正常发布为 NULL）',
  PUBLISH_BY          VARCHAR(64)  DEFAULT NULL,
  PUBLISH_AT          DATETIME     DEFAULT NULL,
  CREATE_BY           VARCHAR(64)  DEFAULT NULL,
  CREATE_DATE         DATETIME     DEFAULT NULL,
  DEL_FLAG            VARCHAR(1)   NOT NULL DEFAULT '0',
  VER_NO              INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (ID),
  UNIQUE KEY UK_BI_RPT_VER (TEMPLATE_ID, VERSION_NO),
  KEY IDX_BI_RPT_VER_TPL (TEMPLATE_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报表模板版本快照（14.1 版本控制，归档只读 C-0-05）';

-- ---- 三角色种子（design D7，PrivilegedRoles 同步）----
INSERT IGNORE INTO erp_admin_role (ID, ROLE_CODE, ROLE_NAME, SORT_ORDER, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
VALUES
  ('role-bi-designer',  'ROLE_BI_DESIGNER', 'BI设计师',   10, '1', 'migrate-195', NOW(), '0', 0),
  ('role-data-gov',     'ROLE_DATA_GOV',    '数据治理专员', 20, '1', 'migrate-195', NOW(), '0', 0),
  ('role-biz-owner',    'ROLE_BIZ_OWNER',   '业务负责人',  30, '1', 'migrate-195', NOW(), '0', 0);
-- 授予 admin（冒烟签署兜底，沿 188/189 先例）
INSERT IGNORE INTO erp_admin_user_role (ID, USER_ID, ROLE_ID)
SELECT CONCAT('ur-admin-', r.ID), u.ID, r.ID
FROM erp_admin_role r JOIN erp_admin_user u ON u.USERNAME = 'admin'
WHERE r.ROLE_CODE IN ('ROLE_BI_DESIGNER', 'ROLE_DATA_GOV', 'ROLE_BIZ_OWNER');

-- ---- 菜单 14.1 四行权限幂等 UPDATE（design D7：BI_DESIGNER 写 + ADMIN）----
UPDATE erp_admin_menu SET PERM = 'ROLE_BI_DESIGNER,ROLE_ADMIN'
WHERE MENU_CODE IN ('14.1.1', '14.1.2', '14.1.3', '14.1.4') AND (PERM IS NULL OR PERM = '');

-- ---- 受控补列：两表缺 UPDATE_BY/UPDATE_DATE（BaseEntity 必填映射，重跑幂等）----
SET @c1 := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='erp_bi_report_test' AND COLUMN_NAME='UPDATE_BY');
SET @s1 := IF(@c1=0, 'ALTER TABLE erp_bi_report_test ADD COLUMN UPDATE_BY VARCHAR(64) DEFAULT NULL AFTER CREATE_BY, ADD COLUMN UPDATE_DATE DATETIME DEFAULT NULL AFTER UPDATE_BY', 'SELECT 1');
PREPARE st1 FROM @s1; EXECUTE st1; DEALLOCATE PREPARE st1;

SET @c2 := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='erp_bi_report_version' AND COLUMN_NAME='UPDATE_BY');
SET @s2 := IF(@c2=0, 'ALTER TABLE erp_bi_report_version ADD COLUMN UPDATE_BY VARCHAR(64) DEFAULT NULL AFTER CREATE_BY, ADD COLUMN UPDATE_DATE DATETIME DEFAULT NULL AFTER UPDATE_BY', 'SELECT 1');
PREPARE st2 FROM @s2; EXECUTE st2; DEALLOCATE PREPARE st2;
