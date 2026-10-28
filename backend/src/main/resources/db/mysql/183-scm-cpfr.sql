-- ============================================================
-- 183-scm-cpfr.sql
-- change: add-cpfr-forecast-replenishment（M12.8 预测补货，spec cpfr-collaboration）
--   8 张 erp_scm_cpfr_* 表（design D1）：
--     agreement      协议+5 参数（BR-4.9-31/34）
--     forecast_head  预测版本（周期标识/状态/幂等键）
--     forecast_line  SKU×周预测量 + 库存/在途/安全库存快照
--     feedback       供应商供给反馈（48h/72h 状态、默认采纳）
--     exception      分歧例外项（±10% 判定，两态闭环）
--     adjust_log     调整历史（协商轮次 + 冻结期调整，C-4.9-11 留痕）
--     plan / plan_line  联合补货计划 + 预测订单行（ZONE 标注/转化登记）
-- ============================================================
-- 幂等：CREATE TABLE IF NOT EXISTS（沿 178-182 惯例）
-- ============================================================

-- ---------- 1. CPFR 协议（每供应商唯一） ----------

CREATE TABLE IF NOT EXISTS erp_scm_cpfr_agreement (
    ID                    VARCHAR(64)   NOT NULL,
    SUPPLIER_ID           VARCHAR(64)   NOT NULL,
    SUPPLIER_NAME         VARCHAR(128)  DEFAULT NULL,
    CYCLE_TYPE            VARCHAR(8)    NOT NULL DEFAULT 'WEEK' COMMENT 'WEEK / MONTH',
    HORIZON_WEEKS         INT           NOT NULL DEFAULT 13 COMMENT '预测展望期（周）',
    FREEZE_WEEKS          INT           DEFAULT NULL COMMENT '冻结期周数（近 N 周不可调）',
    ELASTIC_START_WEEK    INT           DEFAULT NULL COMMENT '弹性区间起始周（含）',
    ELASTIC_END_WEEK      INT           DEFAULT NULL COMMENT '弹性区间结束周（含）',
    ELASTIC_PCT           DECIMAL(6,2)  DEFAULT NULL COMMENT '弹性区间允许调整比例（%）',
    DEVIATION_THRESHOLD_PCT DECIMAL(6,2) NOT NULL DEFAULT 10.00 COMMENT '分歧阈值（%，CPFR_DEVIATION_THRESHOLD）',
    STATUS                VARCHAR(16)   NOT NULL DEFAULT '未启用' COMMENT '未启用/已启用/已暂停/待复盘',
    PAUSE_REASON          VARCHAR(32)   DEFAULT NULL COMMENT '到期/准确率异常/手工停用',
    EXPIRE_DATE           DATE          DEFAULT NULL COMMENT '协议到期日（BR-4.9-34）',
    LAST_PERIOD_TAG       VARCHAR(16)   DEFAULT NULL COMMENT '最近推送周期（默认采纳取上轮量）',
    CREATE_BY             VARCHAR(64)   DEFAULT NULL,
    CREATE_DATE           DATETIME      DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY             VARCHAR(64)   DEFAULT NULL,
    UPDATE_DATE           DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG              VARCHAR(1)    NOT NULL DEFAULT '0',
    VER_NO                INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_CPFR_AGR_SUP (SUPPLIER_ID),
    KEY IDX_CPFR_AGR_STATUS (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='CPFR 协同协议（spec cpfr-collaboration R1）';

-- ---------- 2. 预测版本头 ----------

CREATE TABLE IF NOT EXISTS erp_scm_cpfr_forecast_head (
    ID                    VARCHAR(64)   NOT NULL,
    PERIOD_TAG            VARCHAR(16)   NOT NULL COMMENT '周期标识 2026W44 / 202610',
    STATUS                VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUSHED/CLOSED',
    PUSH_IDEMPOTENT_KEY   VARCHAR(128)  DEFAULT NULL COMMENT '推送幂等键',
    PUSHED_AT             DATETIME      DEFAULT NULL,
    REMARK                VARCHAR(255)  DEFAULT NULL,
    CREATE_BY             VARCHAR(64)   DEFAULT NULL,
    CREATE_DATE           DATETIME      DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY             VARCHAR(64)   DEFAULT NULL,
    UPDATE_DATE           DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG              VARCHAR(1)    NOT NULL DEFAULT '0',
    VER_NO                INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_CPFR_FH_PERIOD (PERIOD_TAG)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='CPFR 预测版本头（spec cpfr-collaboration R2）';

-- ---------- 3. 预测行（SKU × 周 + 快照） ----------

CREATE TABLE IF NOT EXISTS erp_scm_cpfr_forecast_line (
    ID                    VARCHAR(64)   NOT NULL,
    HEAD_ID               VARCHAR(64)   NOT NULL,
    AGREEMENT_ID          VARCHAR(64)   NOT NULL,
    ITEM_CODE             VARCHAR(64)   NOT NULL,
    ITEM_NAME             VARCHAR(128)  DEFAULT NULL,
    WEEK_OFFSET           INT           NOT NULL COMMENT '周偏移 1..HORIZON',
    FORECAST_QTY          DECIMAL(18,3) NOT NULL DEFAULT 0,
    SNAPSHOT_STOCK        DECIMAL(18,3) DEFAULT NULL COMMENT '生成数据包时当前库存快照',
    SNAPSHOT_ONHAND_PO    DECIMAL(18,3) DEFAULT NULL COMMENT '生成数据包时在途 PO 快照',
    SAFETY_STOCK          DECIMAL(18,3) DEFAULT NULL COMMENT '安全库存快照',
    CREATE_BY             VARCHAR(64)   DEFAULT NULL,
    CREATE_DATE           DATETIME      DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY             VARCHAR(64)   DEFAULT NULL,
    UPDATE_DATE           DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG              VARCHAR(1)    NOT NULL DEFAULT '0',
    VER_NO                INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_CPFR_FL (HEAD_ID, AGREEMENT_ID, ITEM_CODE, WEEK_OFFSET),
    KEY IDX_CPFR_FL_AGR (AGREEMENT_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='CPFR 预测行（spec cpfr-collaboration R2）';

-- ---------- 4. 供给能力反馈 ----------

CREATE TABLE IF NOT EXISTS erp_scm_cpfr_feedback (
    ID                    VARCHAR(64)   NOT NULL,
    AGREEMENT_ID          VARCHAR(64)   NOT NULL,
    PERIOD_TAG            VARCHAR(16)   NOT NULL,
    WEEK_OFFSET           INT           NOT NULL,
    ITEM_CODE             VARCHAR(64)   NOT NULL,
    DEMAND_QTY            DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '需求量（取预测行，比对基准）',
    SUPPLY_QTY            DECIMAL(18,3) DEFAULT NULL COMMENT '可供数量（默认采纳=上轮量）',
    CAPACITY_NOTE         VARCHAR(255)  DEFAULT NULL COMMENT '产能约束',
    RISK_NOTE             VARCHAR(255)  DEFAULT NULL COMMENT '风险提示',
    FEEDBACK_SOURCE       VARCHAR(16)   DEFAULT NULL COMMENT 'PORTAL / DEFAULT_ROLL',
    STATUS                VARCHAR(16)   NOT NULL DEFAULT '待反馈' COMMENT '待反馈/已反馈/已催办/已升级/默认采纳',
    SUBMIT_AT             DATETIME      DEFAULT NULL COMMENT '反馈时间（记分卡 CPFR 反馈及时率取数）',
    PERIOD_START_AT       DATETIME      DEFAULT NULL COMMENT '周期开始时间（48h/72h 判定基准）',
    REMIND_AT             DATETIME      DEFAULT NULL COMMENT '催办时间（幂等标记）',
    ESCALATE_AT           DATETIME      DEFAULT NULL COMMENT '升级时间（幂等标记）',
    ESCALATE_TO_ROLE      VARCHAR(32)   DEFAULT NULL COMMENT '升级接收角色（ROLE_PMGR）',
    CREATE_BY             VARCHAR(64)   DEFAULT NULL,
    CREATE_DATE           DATETIME      DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY             VARCHAR(64)   DEFAULT NULL,
    UPDATE_DATE           DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG              VARCHAR(1)    NOT NULL DEFAULT '0',
    VER_NO                INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_CPFR_FB (AGREEMENT_ID, PERIOD_TAG, WEEK_OFFSET, ITEM_CODE),
    KEY IDX_CPFR_FB_STATUS (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='CPFR 供给能力反馈（spec cpfr-collaboration R3，BR-4.9-33）';

-- ---------- 5. 分歧例外项 ----------

CREATE TABLE IF NOT EXISTS erp_scm_cpfr_exception (
    ID                    VARCHAR(64)   NOT NULL,
    AGREEMENT_ID          VARCHAR(64)   NOT NULL,
    PERIOD_TAG            VARCHAR(16)   NOT NULL,
    WEEK_OFFSET           INT           NOT NULL,
    ITEM_CODE             VARCHAR(64)   NOT NULL,
    DEMAND_QTY            DECIMAL(18,3) NOT NULL,
    SUPPLY_QTY            DECIMAL(18,3) NOT NULL,
    DEVIATION_PCT         DECIMAL(6,2)  NOT NULL COMMENT '分歧率（%，负=供给不足）',
    STATUS                VARCHAR(16)   NOT NULL DEFAULT '待协商' COMMENT '待协商/已达成/已驳回',
    CLOSED_AT             DATETIME      DEFAULT NULL,
    CLOSE_BY              VARCHAR(64)   DEFAULT NULL,
    CREATE_BY             VARCHAR(64)   DEFAULT NULL,
    CREATE_DATE           DATETIME      DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY             VARCHAR(64)   DEFAULT NULL,
    UPDATE_DATE           DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG              VARCHAR(1)    NOT NULL DEFAULT '0',
    VER_NO                INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_CPFR_EX (AGREEMENT_ID, PERIOD_TAG, WEEK_OFFSET, ITEM_CODE),
    KEY IDX_CPFR_EX_STATUS (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='CPFR 分歧例外项（spec cpfr-collaboration R4，BR-4.9-32）';

-- ---------- 6. 调整历史（协商轮次 + 冻结期调整留痕） ----------

CREATE TABLE IF NOT EXISTS erp_scm_cpfr_adjust_log (
    ID                    VARCHAR(64)   NOT NULL,
    TARGET_TYPE           VARCHAR(16)   NOT NULL COMMENT 'EXCEPTION / PLAN_LINE',
    TARGET_ID             VARCHAR(64)   NOT NULL,
    AGREEMENT_ID          VARCHAR(64)   DEFAULT NULL,
    PERIOD_TAG            VARCHAR(16)   DEFAULT NULL,
    ACTION                VARCHAR(32)   NOT NULL COMMENT 'SUPPLIER_OPINION/PLAN_CONFIRM/ADJUST_SUBMIT/ADJUST_APPROVED/ADJUST_REJECTED/ADJUST_DIRECT/CONVERT',
    OLD_QTY               DECIMAL(18,3) DEFAULT NULL,
    NEW_QTY               DECIMAL(18,3) DEFAULT NULL,
    OPERATOR              VARCHAR(64)   DEFAULT NULL,
    OPERATOR_ROLE         VARCHAR(64)   DEFAULT NULL,
    REMARK                VARCHAR(500)  DEFAULT NULL,
    OPERATED_AT           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CREATE_BY             VARCHAR(64)   DEFAULT NULL,
    CREATE_DATE           DATETIME      DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY             VARCHAR(64)   DEFAULT NULL,
    UPDATE_DATE           DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG              VARCHAR(1)    NOT NULL DEFAULT '0',
    VER_NO                INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_CPFR_LOG_TARGET (TARGET_TYPE, TARGET_ID),
    KEY IDX_CPFR_LOG_AGR (AGREEMENT_ID, PERIOD_TAG)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='CPFR 调整/协商留痕（spec cpfr-collaboration R4，C-4.9-11）';

-- ---------- 7. 联合补货计划 ----------

CREATE TABLE IF NOT EXISTS erp_scm_cpfr_plan (
    ID                    VARCHAR(64)   NOT NULL,
    AGREEMENT_ID          VARCHAR(64)   NOT NULL,
    PERIOD_TAG            VARCHAR(16)   NOT NULL,
    STATUS                VARCHAR(24)   NOT NULL DEFAULT 'DRAFT'
                        COMMENT 'DRAFT/单方确认/双方确认/已生成订单',
    SUPPLIER_CONFIRM_BY   VARCHAR(64)   DEFAULT NULL,
    SUPPLIER_CONFIRM_AT   DATETIME      DEFAULT NULL,
    INTERNAL_CONFIRM_BY   VARCHAR(64)   DEFAULT NULL,
    INTERNAL_CONFIRM_AT   DATETIME      DEFAULT NULL,
    SINGLE_REMIND_AT      DATETIME      DEFAULT NULL COMMENT '单方确认后 48h 催办（幂等标记）',
    OPEN_EXCEPTION_COUNT  INT           NOT NULL DEFAULT 0 COMMENT '未达成例外数（确认前校验）',
    ACCURACY_PCT          DECIMAL(6,2)  DEFAULT NULL COMMENT '月度准确率（%）',
    ACCURACY_MONTH        VARCHAR(6)    DEFAULT NULL COMMENT '评估月 yyyyMM',
    CREATE_BY             VARCHAR(64)   DEFAULT NULL,
    CREATE_DATE           DATETIME      DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY             VARCHAR(64)   DEFAULT NULL,
    UPDATE_DATE           DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG              VARCHAR(1)    NOT NULL DEFAULT '0',
    VER_NO                INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_CPFR_PLAN (AGREEMENT_ID, PERIOD_TAG),
    KEY IDX_CPFR_PLAN_STATUS (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='CPFR 联合补货计划（spec cpfr-collaboration R5）';

-- ---------- 8. 预测订单行（冻结/弹性标注 + 转化登记） ----------

CREATE TABLE IF NOT EXISTS erp_scm_cpfr_plan_line (
    ID                    VARCHAR(64)   NOT NULL,
    PLAN_ID               VARCHAR(64)   NOT NULL,
    AGREEMENT_ID          VARCHAR(64)   NOT NULL,
    PERIOD_TAG            VARCHAR(16)   NOT NULL,
    ITEM_CODE             VARCHAR(64)   NOT NULL,
    ITEM_NAME             VARCHAR(128)  DEFAULT NULL,
    WEEK_OFFSET           INT           NOT NULL COMMENT '相对确认周的偏移（ZONE 判定输入）',
    QTY                   DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '当前锁定数量',
    ZONE                  VARCHAR(8)    NOT NULL DEFAULT 'FREE' COMMENT 'FREEZE/ELASTIC/FREE（生成时按协议标注）',
    PO_CONVERSION_QTY     DECIMAL(18,3) DEFAULT NULL COMMENT '转化登记：实际到货量',
    CONVERTED_AT          DATETIME      DEFAULT NULL,
    CONVERT_REMARK        VARCHAR(255)  DEFAULT NULL,
    ADJUST_STATUS         VARCHAR(16)   DEFAULT NULL COMMENT 'PENDING_APPROVAL（冻结/超弹性调整审批中）',
    CREATE_BY             VARCHAR(64)   DEFAULT NULL,
    CREATE_DATE           DATETIME      DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY             VARCHAR(64)   DEFAULT NULL,
    UPDATE_DATE           DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG              VARCHAR(1)    NOT NULL DEFAULT '0',
    VER_NO                INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_CPFR_PL (PLAN_ID, ITEM_CODE, WEEK_OFFSET),
    KEY IDX_CPFR_PL_AGR (AGREEMENT_ID, PERIOD_TAG)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='CPFR 预测订单行（spec cpfr-collaboration R5/R6，C-4.9-08）';

-- ============================================================
-- 9. 角色（add-cpfr-forecast-replenishment design D5）：
--    ROLE_SCMDIR 供应链协同管理员（协议启用/参数变更）
--    ROLE_PMGR   采购经理（冻结期 L2 审批节点、72h 升级接收方）
-- ============================================================

INSERT IGNORE INTO erp_admin_role (ID, ROLE_CODE, ROLE_NAME, SORT_ORDER, STATUS, CREATE_BY, DEL_FLAG, VER_NO)
VALUES ('role-scmdir', 'ROLE_SCMDIR', '供应链协同管理员', 85, '1', 'seed', '0', 0),
       ('role-scm-pmgr', 'ROLE_PMGR', '采购经理', 86, '1', 'seed', '0', 0);

-- ---------- 10. M12-8 两叶菜单（自包含 INSERT IGNORE；099 重生成后同 ID 幂等） ----------

INSERT IGNORE INTO erp_admin_menu (ID, PARENT_ID, MENU_CODE, TITLE, PATH, ICON, SORT_ORDER, PERM, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
VALUES ('M12-8',     'M12',     '12.8',     '预测补货',       '',            '', '80', 'ROLE_SCMDIR,ROLE_PMGR,ROLE_PM,ROLE_ADMIN', '1', 'system', NOW(), '0', 0),
       ('M12-8-1',   'M12-8',   '12.8.1',   '协同预测',       '/m/12.8.1',   '', '10', 'ROLE_SCMDIR,ROLE_PMGR,ROLE_PM,ROLE_ADMIN', '1', 'system', NOW(), '0', 0),
       ('M12-8-2',   'M12-8',   '12.8.2',   '补货计划',       '/m/12.8.2',   '', '20', 'ROLE_SCMDIR,ROLE_PMGR,ROLE_PM,ROLE_ADMIN', '1', 'system', NOW(), '0', 0);

-- 幂等补空（099 重生成后同口径；此处只补 PERM 为空的存量行）
UPDATE erp_admin_menu SET PERM = 'ROLE_SCMDIR,ROLE_PMGR,ROLE_PM,ROLE_ADMIN'
 WHERE ID IN ('M12-8', 'M12-8-1', 'M12-8-2') AND (PERM IS NULL OR PERM = '');
