-- ============================================================
-- 189-break-glass.sql
-- change: add-break-glass-console（16.5 紧急权限 SOP-5.6-C，design D1/D5）
--   四张新表：
--     erp_ops_incident        轻量故障工单（BR-5.6-05 处理中校验载体）
--     erp_admin_bg_template   紧急权限模板（最小可用角色集合）
--     erp_admin_bg_request    Break-Glass 申请单（独立 bizType，D1）
--     erp_admin_bg_audit      事后审计报告（签署 + 逾期告警 + 专项评审）
--   种子：ROLE_DUTY_MGR 值班经理 + 授予 admin（冒烟兜底）
--         模板 2 个（DBA-EMERGENCY-02 / DATA-FIX-01，均不含特权角色）
--         样例"处理中"工单
-- ============================================================
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE + 受控 ALTER，可重跑
-- ============================================================

CREATE TABLE IF NOT EXISTS erp_ops_incident (
  ID          VARCHAR(32)  NOT NULL COMMENT '主键',
  INC_NO      VARCHAR(32)  NOT NULL COMMENT '工单编号（INC-YYYYMMDD-XXX）',
  TITLE       VARCHAR(200) NOT NULL COMMENT '故障标题',
  STATUS      VARCHAR(16)  NOT NULL DEFAULT 'PROCESSING' COMMENT 'PROCESSING 处理中 / RESOLVED 已解决 / CLOSED 已关闭',
  HANDLER     VARCHAR(64)  DEFAULT NULL COMMENT '处理人',
  CREATE_BY   VARCHAR(64)  DEFAULT NULL,
  CREATE_DATE DATETIME     DEFAULT NULL,
  UPDATE_BY   VARCHAR(64)  DEFAULT NULL,
  UPDATE_DATE DATETIME     DEFAULT NULL,
  DEL_FLAG    VARCHAR(1)   NOT NULL DEFAULT '0',
  VER_NO      INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (ID),
  UNIQUE KEY UK_INC_NO (INC_NO),
  KEY IDX_INC_STATUS (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='故障工单（Break-Glass 关联载体，BR-5.6-05）';

CREATE TABLE IF NOT EXISTS erp_admin_bg_template (
  ID          VARCHAR(32)   NOT NULL COMMENT '主键',
  CODE        VARCHAR(64)   NOT NULL COMMENT '模板编码（如 DBA-EMERGENCY-02）',
  NAME        VARCHAR(100)  NOT NULL COMMENT '模板名称',
  ROLES_JSON  VARCHAR(1024) NOT NULL COMMENT '最小可用角色集合 JSON 数组（禁特权角色）',
  SCENARIO    VARCHAR(200)  DEFAULT NULL COMMENT '适用场景说明',
  STATUS      VARCHAR(1)    NOT NULL DEFAULT '1' COMMENT '1 启用 / 0 停用',
  CREATE_BY   VARCHAR(64)   DEFAULT NULL,
  CREATE_DATE DATETIME      DEFAULT NULL,
  UPDATE_BY   VARCHAR(64)   DEFAULT NULL,
  UPDATE_DATE DATETIME      DEFAULT NULL,
  DEL_FLAG    VARCHAR(1)    NOT NULL DEFAULT '0',
  VER_NO      INT           NOT NULL DEFAULT 0,
  PRIMARY KEY (ID),
  UNIQUE KEY UK_BG_TEMPLATE_CODE (CODE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='紧急权限模板（SOP-5.6-C step2 最小集合）';

CREATE TABLE IF NOT EXISTS erp_admin_bg_request (
  ID              VARCHAR(32)   NOT NULL COMMENT '主键',
  TARGET_USER_ID  VARCHAR(32)   NOT NULL COMMENT '被授权用户',
  APPLICANT_USER_ID VARCHAR(32) NOT NULL COMMENT '申请人',
  TEMPLATE_ID     VARCHAR(32)   NOT NULL COMMENT '权限模板',
  ROLES_JSON      VARCHAR(1024) NOT NULL COMMENT '模板角色快照（提交时固化）',
  INCIDENT_ID     VARCHAR(32)   NOT NULL COMMENT '关联故障工单（BR-5.6-05）',
  DURATION_HOURS  INT           NOT NULL DEFAULT 2 COMMENT '申请时长（小时，≤BREAK_GLASS_MAX_HOURS）',
  SCREEN_DECLARE  VARCHAR(16)   NOT NULL COMMENT '录屏声明（YES/NO）',
  REASON          VARCHAR(500)  NOT NULL COMMENT '处置说明（≥10 字）',
  STATUS          VARCHAR(24)   NOT NULL DEFAULT 'PENDING_APPROVAL'
                  COMMENT 'PENDING_APPROVAL/ACTIVE/RECLAIMED/REJECTED/AUDITED',
  GRANT_ID        VARCHAR(32)   DEFAULT NULL COMMENT '生效后台账行 ID',
  APPR_INSTANCE_ID VARCHAR(32)  DEFAULT NULL COMMENT '审批实例 ID',
  RECLAIM_AT      DATETIME      DEFAULT NULL COMMENT '回收时间（自动/提前归还）',
  CREATE_BY       VARCHAR(64)   DEFAULT NULL,
  CREATE_DATE     DATETIME      DEFAULT NULL,
  UPDATE_BY       VARCHAR(64)   DEFAULT NULL,
  UPDATE_DATE     DATETIME      DEFAULT NULL,
  DEL_FLAG        VARCHAR(1)    NOT NULL DEFAULT '0',
  VER_NO          INT           NOT NULL DEFAULT 0,
  PRIMARY KEY (ID),
  KEY IDX_BG_REQ_TARGET (TARGET_USER_ID, STATUS),
  KEY IDX_BG_REQ_APR (APPR_INSTANCE_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Break-Glass 申请单（SOP-5.6-C）';

CREATE TABLE IF NOT EXISTS erp_admin_bg_audit (
  ID              VARCHAR(32)  NOT NULL COMMENT '主键',
  APPLY_ID        VARCHAR(32)  NOT NULL COMMENT '来源申请单',
  LEDGER_ID       VARCHAR(32)  NOT NULL COMMENT '授予台账行（生成锚点，幂等唯一）',
  TARGET_USER_ID  VARCHAR(32)  NOT NULL COMMENT '被授权用户',
  GRANT_AT        DATETIME     DEFAULT NULL COMMENT '生效时间',
  RECLAIM_AT      DATETIME     DEFAULT NULL COMMENT '回收时间',
  LOG_COUNT       INT          NOT NULL DEFAULT 0 COMMENT '期间目标账号审计日志条数',
  CONCLUSION      VARCHAR(32)  DEFAULT NULL COMMENT '签署结论：NO_VIOLATION 无越权 / VIOLATION 待查',
  SIGNED_BY       VARCHAR(32)  DEFAULT NULL COMMENT '签署人',
  SIGNED_AT       DATETIME     DEFAULT NULL COMMENT '签署时间',
  SPECIAL_REVIEW  CHAR(1)      NOT NULL DEFAULT '0' COMMENT '月度 ≥3 次专项评审标记',
  LAST_REMIND_AT  DATETIME     DEFAULT NULL COMMENT '逾期告警幂等标记（12h 重复）',
  CREATE_BY       VARCHAR(64)  DEFAULT NULL,
  CREATE_DATE     DATETIME     DEFAULT NULL,
  UPDATE_BY       VARCHAR(64)  DEFAULT NULL,
  UPDATE_DATE     DATETIME     DEFAULT NULL,
  DEL_FLAG        VARCHAR(1)   NOT NULL DEFAULT '0',
  VER_NO          INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (ID),
  UNIQUE KEY UK_BG_AUDIT_LEDGER (LEDGER_ID),
  KEY IDX_BG_AUDIT_SIGN (SIGNED_AT),
  KEY IDX_BG_AUDIT_TARGET (TARGET_USER_ID, CREATE_DATE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Break-Glass 事后审计报告（BR-5.6-08）';

-- ============================================================
-- 2. 值班经理角色（双签节点之一）+ 授予 admin（冒烟/测试兜底，D2）
-- ============================================================
INSERT IGNORE INTO erp_admin_role (ID, ROLE_CODE, ROLE_NAME, SORT_ORDER, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
VALUES ('role-duty-mgr', 'ROLE_DUTY_MGR', '值班经理', 36, '1', 'migrate-189', NOW(), '0', 0);

INSERT IGNORE INTO erp_admin_user_role (ID, USER_ID, ROLE_ID)
VALUES ('ur-admin-duty-mgr', 'user-admin', 'role-duty-mgr');

-- ============================================================
-- 3. 权限模板种子（最小可用集合，均不含特权角色；D6 校验同源）
-- ============================================================
INSERT IGNORE INTO erp_admin_bg_template (ID, CODE, NAME, ROLES_JSON, SCENARIO, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
VALUES ('bgt-dba-02', 'DBA-EMERGENCY-02', '数据库应急维护模板',
        '["ROLE_WAREHOUSE"]', '生产故障抢修（索引重建/死锁处置等，最小集合占位）', '1', 'migrate-189', NOW(), '0', 0),
       ('bgt-fix-01', 'DATA-FIX-01', '数据修复模板',
        '["ROLE_PLANNER"]', '生产数据修复（错误单据更正等，最小集合占位）', '1', 'migrate-189', NOW(), '0', 0);

-- ============================================================
-- 4. 样例"处理中"工单（演示/冒烟；编号幂等靠 UK_INC_NO）
-- ============================================================
INSERT IGNORE INTO erp_ops_incident (ID, INC_NO, TITLE, STATUS, HANDLER, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
VALUES ('inc-demo-1', 'INC-20261029-001', '生产数据库死锁导致工单无法报工', 'PROCESSING', '值班工程师', 'migrate-189', NOW(), '0', 0);
