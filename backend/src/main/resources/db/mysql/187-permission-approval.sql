-- ============================================================
-- 187-permission-approval.sql
-- change: add-permission-approval-flow（16.3 权限审批，design D1）
--   四张新表：
--     erp_admin_perm_request   权限申请单（KIND=NORMAL/EXCEPTION 双态，
--                              承载 SOP-5.6-B step1 字段与状态机）
--     erp_admin_sod_rule       SoD 互斥角色对（BR-5.6-02 互斥矩阵）
--     erp_admin_grant_ledger   授予台账（事实源；APPLY_ID 锚 = C-5.6-01）
--     erp_change_window        变更窗口（周期起止；16.4.1 将来复用）
--   同迁移 DROP 旧例外申请单表（RoleGrantException 被 KIND=EXCEPTION 吸收，
--   design D7；旧表仅测试/冒烟数据）
-- ============================================================
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE + 可重跑
-- ============================================================

CREATE TABLE IF NOT EXISTS erp_admin_perm_request (
  ID                 VARCHAR(32)   NOT NULL COMMENT '主键',
  KIND               VARCHAR(16)   NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL 常规 / EXCEPTION 超基线例外',
  TARGET_USER_ID     VARCHAR(32)   NOT NULL COMMENT '被授权用户（erp_admin_user.ID）',
  APPLICANT_USER_ID  VARCHAR(32)   NOT NULL COMMENT '申请人（可为管理员代提）',
  ROLES_JSON         VARCHAR(1024) NOT NULL COMMENT '目标角色码 JSON 数组',
  RISK_LEVEL         VARCHAR(16)   NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/SENSITIVE/PRIVILEGED（取目标角色最高）',
  DATA_SCOPE         VARCHAR(500)  NOT NULL COMMENT '数据范围（法人/组织单元/部门）必填留痕',
  REASON             VARCHAR(500)  NOT NULL COMMENT '业务理由（≥10 字）',
  TICKET_NO          VARCHAR(100)  DEFAULT NULL COMMENT '特权申请必附故障单/项目编号',
  VALID_MONTHS       INT           NOT NULL DEFAULT 12 COMMENT '有效期月数（≤12）',
  ACTIVATION_MODE    VARCHAR(16)   NOT NULL DEFAULT 'IMMEDIATE' COMMENT 'IMMEDIATE 立即 / SCHEDULED 指定时点',
  ACTIVATE_AT        DATETIME      DEFAULT NULL COMMENT '指定生效时点',
  SOD_SNAPSHOT_JSON  VARCHAR(2000) DEFAULT NULL COMMENT '提交时预检快照（超基线/互斥/现有权限）',
  STATUS             VARCHAR(24)   NOT NULL DEFAULT 'PENDING_APPROVAL'
                     COMMENT 'PENDING_APPROVAL/APPROVED_PENDING/ACTIVE/REJECTED/CANCELLED',
  APPR_INSTANCE_ID   VARCHAR(32)   DEFAULT NULL COMMENT '审批实例 ID',
  GRANT_ID           VARCHAR(32)   DEFAULT NULL COMMENT '生效后台账行 ID',
  CREATE_BY          VARCHAR(64)   DEFAULT NULL,
  CREATE_DATE        DATETIME      DEFAULT NULL,
  UPDATE_BY          VARCHAR(64)   DEFAULT NULL,
  UPDATE_DATE        DATETIME      DEFAULT NULL,
  DEL_FLAG           VARCHAR(1)    NOT NULL DEFAULT '0',
  VER_NO             INT           NOT NULL DEFAULT 0,
  PRIMARY KEY (ID),
  KEY IDX_PERM_REQ_TARGET (TARGET_USER_ID, STATUS),
  KEY IDX_PERM_REQ_APR (APPR_INSTANCE_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限申请单（SOP-5.6-B）';

CREATE TABLE IF NOT EXISTS erp_admin_sod_rule (
  ID          VARCHAR(32)  NOT NULL COMMENT '主键',
  ROLE_A      VARCHAR(64)  NOT NULL COMMENT '互斥角色 A（规范序 A < B）',
  ROLE_B      VARCHAR(64)  NOT NULL COMMENT '互斥角色 B',
  REMARK      VARCHAR(200) DEFAULT NULL COMMENT '互斥说明',
  CREATE_BY   VARCHAR(64)  DEFAULT NULL,
  CREATE_DATE DATETIME     DEFAULT NULL,
  UPDATE_BY   VARCHAR(64)  DEFAULT NULL,
  UPDATE_DATE DATETIME     DEFAULT NULL,
  DEL_FLAG    VARCHAR(1)   NOT NULL DEFAULT '0',
  VER_NO      INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (ID),
  UNIQUE KEY UK_SOD_PAIR (ROLE_A, ROLE_B)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='职责分离 SoD 互斥矩阵（BR-5.6-02）';

CREATE TABLE IF NOT EXISTS erp_admin_grant_ledger (
  ID             VARCHAR(32)   NOT NULL COMMENT '主键',
  APPLY_ID       VARCHAR(32)   NOT NULL COMMENT '来源申请单（C-5.6-01 锚点）',
  TARGET_USER_ID VARCHAR(32)   NOT NULL COMMENT '被授予用户',
  ROLE_CODE      VARCHAR(64)   NOT NULL COMMENT '授予角色',
  SOURCE         VARCHAR(16)   NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/EXCEPTION',
  GRANT_AT       DATETIME      NOT NULL COMMENT '授予时间',
  EXPIRE_AT      DATETIME      NOT NULL COMMENT '到期时间',
  STATUS         VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/RECLAIMED/EXPIRED',
  RECLAIM_AT     DATETIME      DEFAULT NULL COMMENT '回收时间',
  SNAPSHOT_JSON  VARCHAR(2000) DEFAULT NULL COMMENT '回收前权限快照',
  REMIND_AT      DATETIME      DEFAULT NULL COMMENT '到期前提醒时间（7 日提醒幂等标记）',
  CREATE_BY      VARCHAR(64)   DEFAULT NULL,
  CREATE_DATE    DATETIME      DEFAULT NULL,
  UPDATE_BY      VARCHAR(64)   DEFAULT NULL,
  UPDATE_DATE    DATETIME      DEFAULT NULL,
  DEL_FLAG       VARCHAR(1)    NOT NULL DEFAULT '0',
  VER_NO         INT           NOT NULL DEFAULT 0,
  PRIMARY KEY (ID),
  KEY IDX_LEDGER_APPLY (APPLY_ID),
  KEY IDX_LEDGER_USER (TARGET_USER_ID, STATUS),
  KEY IDX_LEDGER_EXPIRE (EXPIRE_AT, STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限授予台账（生命周期事实源）';

CREATE TABLE IF NOT EXISTS erp_change_window (
  ID           VARCHAR(32)  NOT NULL COMMENT '主键',
  NAME         VARCHAR(100) NOT NULL COMMENT '窗口名称（如：周三夜间窗口）',
  DAY_OF_WEEK  TINYINT      NOT NULL COMMENT '1=周一 .. 7=周日',
  START_TIME   TIME         NOT NULL COMMENT '窗口开始时刻（含）',
  END_TIME     TIME         NOT NULL COMMENT '窗口结束时刻（不含）',
  STATUS       VARCHAR(1)   NOT NULL DEFAULT '1' COMMENT '1 启用 / 0 停用',
  CREATE_BY    VARCHAR(64)  DEFAULT NULL,
  CREATE_DATE  DATETIME     DEFAULT NULL,
  UPDATE_BY    VARCHAR(64)  DEFAULT NULL,
  UPDATE_DATE  DATETIME     DEFAULT NULL,
  DEL_FLAG     VARCHAR(1)   NOT NULL DEFAULT '0',
  VER_NO       INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (ID),
  KEY IDX_WINDOW_DAY (DAY_OF_WEEK, STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='变更窗口（周期；16.4.1 复用）';

-- ============================================================
-- 1.5 按人节点支撑（design D3）：ROLE_REQUIRED 允许为空
--     （按人节点 signer 必填、role 为空；角色节点反之）
-- ============================================================

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'erp_sys_approval_task'
                   AND COLUMN_NAME = 'ROLE_REQUIRED'
                   AND IS_NULLABLE = 'NO');
SET @ddl := IF(@has_col > 0,
    'ALTER TABLE erp_sys_approval_task MODIFY COLUMN ROLE_REQUIRED VARCHAR(64) NULL COMMENT ''要求角色（按人节点为 NULL，按 SIGNER 判定）''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 台账 7 日提醒幂等标记（design D6）
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'erp_admin_grant_ledger'
                   AND COLUMN_NAME = 'REMIND_AT');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE erp_admin_grant_ledger ADD COLUMN REMIND_AT DATETIME NULL COMMENT ''到期前提醒时间（7 日提醒幂等标记）'' AFTER SNAPSHOT_JSON',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================
-- 2. SoD 互斥种子（规格 SOP-5.6-B step2 三对样例 → 本系统实际角色码）
--    采购订单创建↔采购订单审批 / 付款申请↔付款审批 / 主数据维护↔主数据审批
--    （第三对落位为客户主数据：销售维护 ↔ 销售总监审批——系统内 MDM
--      维护按域角色分工，取最贴近的一对；沙盘可增删调整）
-- ============================================================

INSERT IGNORE INTO erp_admin_sod_rule (ID, ROLE_A, ROLE_B, REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
VALUES ('sod-seed-1', 'ROLE_PM', 'ROLE_PURCHASER',
        '采购订单创建 ↔ 采购订单审批（规格样例）', 'migrate-187', NOW(), '0', 0),
       ('sod-seed-2', 'ROLE_FINANCE', 'ROLE_FINANCE_MGR',
        '付款申请 ↔ 付款审批（规格样例）', 'migrate-187', NOW(), '0', 0),
       ('sod-seed-3', 'ROLE_SALES', 'ROLE_SALES_DIRECTOR',
        '主数据维护 ↔ 主数据审批（规格样例，落位客户主数据）', 'migrate-187', NOW(), '0', 0);

-- 纠正早期非规范序种子（A 必须字典序小于 B；幂等）
UPDATE erp_admin_sod_rule
   SET ROLE_A = 'ROLE_PM', ROLE_B = 'ROLE_PURCHASER'
 WHERE ID = 'sod-seed-1' AND ROLE_A = 'ROLE_PURCHASER' AND ROLE_B = 'ROLE_PM';

-- ============================================================
-- 3. 退役旧例外申请单表（design D7：RoleGrantException 被
--    KIND=EXCEPTION 申请单吸收；仅测试/冒烟数据，DROP 安全）
-- ============================================================

DROP TABLE IF EXISTS erp_admin_role_grant_request;
