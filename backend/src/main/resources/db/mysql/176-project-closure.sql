-- ============================================================
-- 176-project-closure.sql
-- 项目结项与归档（M11.12，设计 4.8 流程七；change add-project-closure）
--   ①erp_pm_closure_check 结项检查单头（一项目一单，DRAFT/FROZEN，有条件标记）
--   ②erp_pm_closure_check_item 检查单行（固定 6 项 + 自定义行；数值证据快照 EVIDENCE_JSON）
--   ③erp_pm_closure_remediation 有条件结项遗留工单（90 天闭环，逾期升级 ROLE_PMO，BR-4.8-28）
--   ④erp_pm_closure_settlement 财务成本结算不可变快照（BR-4.8-29 十年归档锚点）
--   ⑤M11-12 四叶菜单 PERM 幂等 UPDATE（零新角色）
-- 幂等：CREATE IF NOT EXISTS + UPDATE 只补空 + INSERT IGNORE
-- ============================================================

-- ---------- 1. 结项检查单头 ----------

CREATE TABLE IF NOT EXISTS erp_pm_closure_check (
    ID                 VARCHAR(32) NOT NULL,
    PROJECT_ID         VARCHAR(32) NOT NULL COMMENT '项目 erp_pm_project.ID',
    PROJECT_NO         VARCHAR(32) NULL COMMENT '冗余项目编号（归档可查）',
    STATUS             VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
                       COMMENT 'DRAFT 起草/可改 | FROZEN 已提交审批只读',
    CONDITIONAL_FLAG   CHAR(1) NOT NULL DEFAULT '0' COMMENT '有条件结项标记（PMO 有条件通过=1）',
    APPROVAL_ID        VARCHAR(64) NULL COMMENT '审批实例（ApprovalEngine）',
    RES_RELEASE_JSON   VARCHAR(4000) NULL COMMENT '资源释放清单快照（归档时生成：参与人/工时汇总）',
    SUBMIT_BY          VARCHAR(64) NULL,
    SUBMIT_DATE        DATETIME NULL,
    AUDIT_OPINION      VARCHAR(1000) NULL COMMENT 'PMO 审批意见（有条件通过说明）',
    CREATE_BY          VARCHAR(64) NULL,
    CREATE_DATE        DATETIME NULL,
    UPDATE_BY          VARCHAR(64) NULL,
    UPDATE_DATE        DATETIME NULL,
    DEL_FLAG           CHAR(1) NOT NULL DEFAULT '0',
    VER_NO             INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_CLOSURE_CHECK_PROJECT (PROJECT_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结项检查单（S-4.8-07，C-4.8-05 审批依据留档）';

-- ---------- 2. 检查单行（数值证据快照） ----------

CREATE TABLE IF NOT EXISTS erp_pm_closure_check_item (
    ID                 VARCHAR(32) NOT NULL,
    CHECK_ID           VARCHAR(32) NOT NULL,
    LINE_NO            INT NOT NULL DEFAULT 1,
    ITEM_KEY           VARCHAR(30) NOT NULL COMMENT 'WBS|MS|ACCEPT|COST|IP|DOC|CUSTOM_x',
    ITEM_NAME          VARCHAR(200) NOT NULL,
    MANDATORY_FLAG     CHAR(1) NOT NULL DEFAULT '1' COMMENT '强制项 1（C-4.8-05 硬阻断）|非强制 0（遗留通道）',
    AUTO_FLAG          CHAR(1) NOT NULL DEFAULT '1' COMMENT '自动校验 1 |人工确认 0',
    RESULT             VARCHAR(10) NOT NULL DEFAULT 'PENDING' COMMENT 'PASS|FAIL|PENDING',
    EVIDENCE_JSON      VARCHAR(1000) NULL COMMENT '数值证据快照（非布尔：doneRate/openMs/deviation/missingIp…）',
    EVIDENCE_AT        DATETIME NULL COMMENT '快照时点（重新校验刷新）',
    ATTACH_IDS         VARCHAR(1000) NULL COMMENT '人工项凭证附件（BIZ_TYPE=PROJECT_CLOSURE，勾 PASS 必传 ≥1）',
    CONFIRM_BY         VARCHAR(64) NULL COMMENT '人工确认人',
    CONFIRM_DATE       DATETIME NULL,
    REMARK             VARCHAR(500) NULL,
    CREATE_BY          VARCHAR(64) NULL,
    CREATE_DATE        DATETIME NULL,
    UPDATE_BY          VARCHAR(64) NULL,
    UPDATE_DATE        DATETIME NULL,
    DEL_FLAG           CHAR(1) NOT NULL DEFAULT '0',
    VER_NO             INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_CLOSURE_ITEM_CHECK (CHECK_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结项检查项（自动项落数值证据，审批后冻结留档）';

-- ---------- 3. 遗留跟踪工单（BR-4.8-28） ----------

CREATE TABLE IF NOT EXISTS erp_pm_closure_remediation (
    ID                 VARCHAR(32) NOT NULL,
    CHECK_ID           VARCHAR(32) NOT NULL,
    PROJECT_ID         VARCHAR(32) NOT NULL,
    CONTENT            VARCHAR(1000) NOT NULL COMMENT '遗留事项描述',
    SOURCE_ITEM_KEY    VARCHAR(30) NULL COMMENT '来源检查项（自定义行遗留时回填）',
    OWNER_ID           VARCHAR(64) NOT NULL COMMENT '责任人',
    OWNER_NAME         VARCHAR(64) NULL,
    DUE_DATE           DATETIME NULL COMMENT '期限=提交日+90 天（BR-4.8-28）',
    STATUS             VARCHAR(10) NOT NULL DEFAULT 'DRAFT'
                       COMMENT 'DRAFT PMO 审批前草稿 |OPEN 生效跟踪 |DONE 已关闭',
    ESCALATED_FLAG     CHAR(1) NOT NULL DEFAULT '0' COMMENT '逾期已升级 ROLE_PMO（幂等）',
    ESCALATED_AT       DATETIME NULL,
    CLOSE_NOTE         VARCHAR(500) NULL,
    CLOSE_BY           VARCHAR(64) NULL,
    CLOSE_DATE         DATETIME NULL,
    CREATE_BY          VARCHAR(64) NULL,
    CREATE_DATE        DATETIME NULL,
    UPDATE_BY          VARCHAR(64) NULL,
    UPDATE_DATE        DATETIME NULL,
    DEL_FLAG           CHAR(1) NOT NULL DEFAULT '0',
    VER_NO             INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_CLOSURE_REM_CHECK (CHECK_ID),
    KEY IDX_CLOSURE_REM_PROJECT (PROJECT_ID),
    KEY IDX_CLOSURE_REM_STATUS (STATUS, DUE_DATE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='有条件结项遗留跟踪工单（90 天闭环，归档后仍可流转）';

-- ---------- 4. 结算不可变快照 ----------

CREATE TABLE IF NOT EXISTS erp_pm_closure_settlement (
    ID                 VARCHAR(32) NOT NULL,
    PROJECT_ID         VARCHAR(32) NOT NULL,
    PROJECT_NO         VARCHAR(32) NULL,
    COLLECT_TOTAL      DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '成本归集总额（人工+材料+外协+其他）',
    BASELINE_AMT       DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '预算基线总额',
    DEVIATION_RATE     DECIMAL(8,4) NOT NULL DEFAULT 0 COMMENT '偏差率（归集-基线）/基线',
    UNFROZEN_FLAG      CHAR(1) NOT NULL DEFAULT '0' COMMENT '确认时曾冻结并已解除 1|0',
    CONFIRM_BY         VARCHAR(64) NOT NULL COMMENT '财务确认人（ROLE_FINANCE/FIN_MGR）',
    CONFIRM_DATE       DATETIME NOT NULL,
    CREATE_BY          VARCHAR(64) NULL,
    CREATE_DATE        DATETIME NULL,
    UPDATE_BY          VARCHAR(64) NULL,
    UPDATE_DATE        DATETIME NULL,
    DEL_FLAG           CHAR(1) NOT NULL DEFAULT '0',
    VER_NO             INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_CLOSURE_SETTLE_PROJECT (PROJECT_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结项成本结算快照（生成即不可变，无 UPDATE 业务路径，BR-4.8-29）';

-- ---------- 5. M11-12 四叶菜单 PERM（幂等 UPDATE 只补空值；099 已有菜单行） ----------

UPDATE erp_admin_menu SET PERM = 'ROLE_PMO,ROLE_PM,ROLE_ADMIN'
 WHERE ID = 'M11-12'
   AND (PERM IS NULL OR PERM = '');
UPDATE erp_admin_menu SET PERM = 'ROLE_PMO,ROLE_PM,ROLE_ADMIN'
 WHERE ID = 'M11-12-1'
   AND (PERM IS NULL OR PERM = '');
UPDATE erp_admin_menu SET PERM = 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_PMO,ROLE_ADMIN'
 WHERE ID = 'M11-12-2'
   AND (PERM IS NULL OR PERM = '');
UPDATE erp_admin_menu SET PERM = 'ROLE_PMO,ROLE_PM,ROLE_ADMIN'
 WHERE ID = 'M11-12-3'
   AND (PERM IS NULL OR PERM = '');
UPDATE erp_admin_menu SET PERM = 'ROLE_PMO,ROLE_ADMIN'
 WHERE ID = 'M11-12-4'
   AND (PERM IS NULL OR PERM = '');
