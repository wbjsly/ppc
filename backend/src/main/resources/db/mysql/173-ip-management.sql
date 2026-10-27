-- ============================================================
-- 173-ip-management.sql
-- 知识产权管理（M11.8，设计 4.8 流程六；change add-ip-management）
--   ①erp_pm_ip_agreement IP 归属协议（结构化承载，BR-4.8-25 比例校验）
--   ②erp_pm_achievement 研发成果（WBS 追溯 C-4.8-07 + 确认状态机）
--   ③erp_pm_achievement_member 参与人员工时快照（仲裁证据链按人可查）
--   ④erp_pm_ip_arbitration 冲突仲裁单（双入口，C-4.8-04）
--   ⑤erp_pm_ip_claim 双方权属主张
--   ⑥erp_pm_ip_asset IP 资产档案（只增不改，BR-4.8-26；UK 一成果一档）
--   ⑦erp_pm_ip_asset_status_log 申请状态变更历史（append-only）
--   ⑧ROLE_RD_LEAD / ROLE_LEGAL 角色 + M11-8 四叶 PERM 幂等 UPDATE
-- 幂等：CREATE TABLE IF NOT EXISTS + UPDATE 只补空（沿 167/171 教训不依赖 099）
-- ============================================================

-- ---------- 1. IP 归属协议（FR-4.8-6-2 数据基座） ----------

CREATE TABLE IF NOT EXISTS erp_pm_ip_agreement (
    ID              VARCHAR(32) NOT NULL,
    PROJECT_ID      VARCHAR(32) NOT NULL COMMENT '关联项目',
    COOP_MODE       VARCHAR(20) NOT NULL COMMENT 'INDEP 独立研发|COOP 合作研发|ENTRUST 委托研发',
    COMPANY_RATE    DECIMAL(8,4) NOT NULL DEFAULT 100 COMMENT '公司权属比例%（INDEP=100）',
    PARTNER_NAME    VARCHAR(100) NULL COMMENT '合作方名称（COOP 必填）',
    PARTNER_RATE    DECIMAL(8,4) NULL COMMENT '合作方权属比例%（COOP 时合计必须=100，BR-4.8-25）',
    ENTRUSTEE_NAME  VARCHAR(100) NULL COMMENT '委托方名称（ENTRUST 必填）',
    SIGN_DATE       DATE NOT NULL COMMENT '协议签署日期',
    EFFECTIVE       CHAR(1) NOT NULL DEFAULT '1' COMMENT '1=生效（后签覆盖前签，旧协议置 0 留痕）',
    REMARK          VARCHAR(500) NULL,
    UPDATE_BY       VARCHAR(64) NULL,
    UPDATE_DATE     DATETIME NULL,
    CREATE_BY       VARCHAR(64) NULL,
    CREATE_DATE     DATETIME NULL,
    DEL_FLAG        CHAR(1) NOT NULL DEFAULT '0',
    VER_NO          INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_PM_IPA_PROJECT (PROJECT_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IP 归属协议（每项目一份生效）';

-- ---------- 2. 研发成果（FR-4.8-6-1 + C-4.8-07） ----------

CREATE TABLE IF NOT EXISTS erp_pm_achievement (
    ID                VARCHAR(32) NOT NULL,
    ACHIEVE_NO        VARCHAR(32) NOT NULL COMMENT 'IPR+yyyyMMdd-序号',
    PROJECT_ID        VARCHAR(32) NOT NULL,
    WBS_ID            VARCHAR(32) NOT NULL COMMENT '任务级节点（L1 硬阻断 C-4.8-07）',
    TYPE              VARCHAR(20) NOT NULL COMMENT 'PATENT 专利|SOFT_CP 软著|TRADE_SECRET 技术秘密|TECH_DOC 技术文档',
    NAME              VARCHAR(200) NOT NULL,
    DESCRIPTION       VARCHAR(2000) NULL COMMENT '<20 字 L4 提示补充（不阻断）',
    COMPLETE_DATE     DATE NULL COMMENT '完成日期（默认取任务最后工时日）',
    OWNERSHIP_RESULT  VARCHAR(500) NULL COMMENT '权属判定 JSON {company:60,partner:30}（裁决可覆盖）',
    STATUS            VARCHAR(20) NOT NULL COMMENT 'DRAFT|PENDING_CONFIRM|CONFIRMED|LEGAL_REVIEW',
    ARBITRATING_FLAG  CHAR(1) NOT NULL DEFAULT '0' COMMENT '1=仲裁在途（防重依据）',
    CONFIRM_BY        VARCHAR(64) NULL,
    CONFIRM_DATE      DATETIME NULL,
    OBJECTION_TYPE    VARCHAR(20) NULL COMMENT 'OBJ_MULTI 多方主张|OBJ_UNILATERAL 单方争议',
    REJECT_REASON     VARCHAR(500) NULL COMMENT '异议/驳回说明',
    UPDATE_BY         VARCHAR(64) NULL,
    UPDATE_DATE       DATETIME NULL,
    CREATE_BY         VARCHAR(64) NULL,
    CREATE_DATE       DATETIME NULL,
    DEL_FLAG          CHAR(1) NOT NULL DEFAULT '0',
    VER_NO            INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_PM_ACH_PROJECT (PROJECT_ID),
    KEY IDX_PM_ACH_WBS_TYPE (WBS_ID, TYPE),
    KEY IDX_PM_ACH_STATUS (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='研发成果（确认后永久保留 BR-4.8-26）';

-- ---------- 3. 成果参与人员（工时快照，独立表按人可查） ----------

CREATE TABLE IF NOT EXISTS erp_pm_achievement_member (
    ID          VARCHAR(32) NOT NULL,
    ACHIEVE_ID  VARCHAR(32) NOT NULL,
    EMP_ID      VARCHAR(64) NOT NULL,
    EMP_NAME    VARCHAR(64) NULL,
    HOURS       DECIMAL(8,2) NOT NULL DEFAULT 0 COMMENT '工时小时数快照（后续工时变动不影响）',
    UPDATE_BY   VARCHAR(64) NULL,
    UPDATE_DATE DATETIME NULL,
    CREATE_BY   VARCHAR(64) NULL,
    CREATE_DATE DATETIME NULL,
    DEL_FLAG    CHAR(1) NOT NULL DEFAULT '0',
    VER_NO      INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_PM_ACHM_ACH (ACHIEVE_ID),
    KEY IDX_PM_ACHM_EMP (EMP_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成果参与人员工时快照';

-- ---------- 4. 冲突仲裁单（FR-4.8-6-4 + C-4.8-04，双入口） ----------

CREATE TABLE IF NOT EXISTS erp_pm_ip_arbitration (
    ID              VARCHAR(32) NOT NULL,
    ARB_NO          VARCHAR(32) NOT NULL COMMENT 'IAR+yyyyMMdd-序号',
    ACHIEVE_ID      VARCHAR(32) NOT NULL,
    SOURCE          VARCHAR(20) NOT NULL COMMENT 'CONFIRM_OBJECTION 确认异议带出|MANUAL 法务独立申请',
    STATUS          VARCHAR(24) NOT NULL COMMENT 'CLAIMS_SUBMITTED|LEGAL_REVIEWING|OPINION_ISSUED|PARTY_CONFIRMED|ADJUDICATED|ESCALATED_EXTERNAL',
    OPINION         VARCHAR(2000) NULL COMMENT '法务仲裁意见',
    OPINION_BY      VARCHAR(64) NULL,
    OPINION_DATE    DATETIME NULL,
    ADJUDICATE_RESULT VARCHAR(500) NULL COMMENT '裁决权属 JSON（回写成果覆盖原判定）',
    ESCALATE_REASON VARCHAR(500) NULL COMMENT '意见不被接受→升级外部说明',
    CONFIRM_BY      VARCHAR(64) NULL,
    CONFIRM_DATE    DATETIME NULL,
    UPDATE_BY       VARCHAR(64) NULL,
    UPDATE_DATE     DATETIME NULL,
    CREATE_BY       VARCHAR(64) NULL,
    CREATE_DATE     DATETIME NULL,
    DEL_FLAG        CHAR(1) NOT NULL DEFAULT '0',
    VER_NO          INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_PM_IPARB_ACH (ACHIEVE_ID),
    KEY IDX_PM_IPARB_STATUS (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IP 权属冲突仲裁单（同一成果一个在途）';

-- ---------- 5. 双方权属主张（依据留痕永久保留） ----------

CREATE TABLE IF NOT EXISTS erp_pm_ip_claim (
    ID            VARCHAR(32) NOT NULL,
    ARB_ID        VARCHAR(32) NOT NULL,
    CLAIMANT      VARCHAR(20) NOT NULL COMMENT 'PARTY_A|PARTY_B',
    CLAIMANT_NAME VARCHAR(100) NOT NULL COMMENT '主张方名称（公司/合作方/个人）',
    CLAIM_RATE    DECIMAL(8,4) NULL COMMENT '主张权属比例%',
    EVIDENCE      VARCHAR(2000) NULL COMMENT '主张依据说明（附件走 erp_sys_attachment）',
    REMARK        VARCHAR(500) NULL,
    UPDATE_BY     VARCHAR(64) NULL,
    UPDATE_DATE   DATETIME NULL,
    CREATE_BY     VARCHAR(64) NULL,
    CREATE_DATE   DATETIME NULL,
    DEL_FLAG      CHAR(1) NOT NULL DEFAULT '0',
    VER_NO        INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_PM_IPCL_ARB (ARB_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仲裁双方权属主张';

-- ---------- 6. IP 资产档案（FR-4.8-6-5，只增不改，一成果一档） ----------

CREATE TABLE IF NOT EXISTS erp_pm_ip_asset (
    ID                  VARCHAR(32) NOT NULL,
    ACHIEVE_ID          VARCHAR(32) NOT NULL COMMENT '唯一（UK，幂等生成依据）',
    ACHIEVE_NO          VARCHAR(32) NULL COMMENT '成果编号快照',
    NAME                VARCHAR(200) NOT NULL COMMENT '成果名称快照',
    TYPE                VARCHAR(20) NOT NULL COMMENT '成果类型快照',
    PROJECT_ID          VARCHAR(32) NOT NULL,
    OWNERSHIP_RESULT    VARCHAR(500) NOT NULL COMMENT '权属快照 JSON',
    COMPLETE_DATE       DATE NULL,
    PROTECT_EXPIRE_DATE DATE NULL COMMENT '保护期届满（专利+20y/软著+50y/其余 NULL 不设期限）',
    APPLY_STATUS        VARCHAR(20) NOT NULL DEFAULT 'NOT_APPLIED' COMMENT 'NOT_APPLIED|SUBMITTED|GRANTED|REJECTED',
    APPLY_UPDATED_AT    DATETIME NULL,
    UPDATE_BY           VARCHAR(64) NULL,
    UPDATE_DATE         DATETIME NULL,
    CREATE_BY           VARCHAR(64) NULL,
    CREATE_DATE         DATETIME NULL,
    DEL_FLAG            CHAR(1) NOT NULL DEFAULT '0',
    VER_NO              INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_PM_IPA_ASSET (ACHIEVE_ID),
    KEY IDX_PM_IPA_PROJECT (PROJECT_ID),
    KEY IDX_PM_IPA_EXPIRE (PROTECT_EXPIRE_DATE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IP 资产档案（只增不改 BR-4.8-26）';

-- ---------- 7. 申请状态变更历史（append-only 留痕） ----------

CREATE TABLE IF NOT EXISTS erp_pm_ip_asset_status_log (
    ID           VARCHAR(32) NOT NULL,
    ASSET_ID     VARCHAR(32) NOT NULL,
    FROM_STATUS  VARCHAR(20) NULL,
    TO_STATUS    VARCHAR(20) NOT NULL,
    OPERATOR     VARCHAR(64) NULL,
    OPERATE_DATE DATETIME NOT NULL,
    REMARK       VARCHAR(500) NULL,
    UPDATE_BY    VARCHAR(64) NULL,
    UPDATE_DATE  DATETIME NULL,
    CREATE_BY    VARCHAR(64) NULL,
    CREATE_DATE  DATETIME NULL,
    DEL_FLAG     CHAR(1) NOT NULL DEFAULT '0',
    VER_NO       INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_PM_IPASL_ASSET (ASSET_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IP 资产申请状态变更历史';

-- ---------- 8. 角色种子（研发负责人 + 法务） ----------

INSERT IGNORE INTO erp_admin_role (ID, ROLE_CODE, ROLE_NAME, SORT_ORDER, STATUS, CREATE_BY, DEL_FLAG, VER_NO)
VALUES ('role-rd-lead', 'ROLE_RD_LEAD', '研发负责人', 27, '1', 'system', '0', 0),
       ('role-legal',   'ROLE_LEGAL',   '法务',       28, '1', 'system', '0', 0);

-- ---------- 9. M11-8 四叶菜单 PERM（幂等 UPDATE 只补空值，沿 167/171 范式） ----------

UPDATE erp_admin_menu SET PERM = 'ROLE_PM,ROLE_RD_LEAD,ROLE_PMO,ROLE_ADMIN'
 WHERE ID = 'M11-8-1'
   AND (PERM IS NULL OR PERM = '');
UPDATE erp_admin_menu SET PERM = 'ROLE_RD_LEAD,ROLE_LEGAL,ROLE_PMO,ROLE_ADMIN'
 WHERE ID = 'M11-8-2'
   AND (PERM IS NULL OR PERM = '');
UPDATE erp_admin_menu SET PERM = 'ROLE_LEGAL,ROLE_PMO,ROLE_ADMIN'
 WHERE ID = 'M11-8-3'
   AND (PERM IS NULL OR PERM = '');
UPDATE erp_admin_menu SET PERM = 'ROLE_RD_LEAD,ROLE_LEGAL,ROLE_PMO,ROLE_FIN_MGR,ROLE_ADMIN'
 WHERE ID = 'M11-8-4'
   AND (PERM IS NULL OR PERM = '');
