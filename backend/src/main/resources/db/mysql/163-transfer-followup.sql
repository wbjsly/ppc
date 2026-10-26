-- ============================================================
-- 163 入转调离缺口补齐（add-transfer-followup）
--   ①hr_social_change 社保增减员/转移单据（social-insurance-ops）
--   ②hr_transfer 加列：缴纳地变更/调薪后月薪（employee-transfer）
--   ③hr_exit 加列：经济补偿金/来源/挽留意见/减员豁免
-- ============================================================

-- ---------- 1. 社保增减员/转移单据 ----------

CREATE TABLE IF NOT EXISTS hr_social_change (
    ID             VARCHAR(32) NOT NULL,
    CHG_NO         VARCHAR(32) NOT NULL COMMENT 'SC+yyyyMMdd-序号',
    CHG_TYPE       VARCHAR(10) NOT NULL COMMENT 'ADD 增员/REMOVE 减员/TRANSFER 转移',
    EMP_ID         VARCHAR(32) NOT NULL,
    EMP_NAME       VARCHAR(64) NULL,
    EMP_NO         VARCHAR(32) NULL,
    TARGET_PLACE   VARCHAR(64) NULL COMMENT '目标缴纳地（ADD/TRANSFER 必填）',
    SRC_TYPE       VARCHAR(20) NOT NULL COMMENT '触发源 ONBOARDING/EXIT/TRANSFER/MANUAL',
    SRC_ID         VARCHAR(32) NULL COMMENT '触发人事单号',
    DECLARE_DATE   DATETIME NULL COMMENT '申报日期',
    RECEIPT_NO     VARCHAR(64) NULL COMMENT '回执号',
    OPERATOR       VARCHAR(64) NULL COMMENT '经办人',
    CHANGE_DATE    DATE NULL COMMENT '生效日期（REMOVE 缺省取离职日）',
    STATUS         VARCHAR(12) NOT NULL COMMENT 'DRAFT/DECLARED/DONE/FAILED/VOID',
    NOTE           VARCHAR(200) NULL,
    CREATE_BY      VARCHAR(64) NULL,
    CREATE_DATE    DATETIME NULL,
    UPDATE_BY      VARCHAR(64) NULL,
    UPDATE_DATE    DATETIME NULL,
    DEL_FLAG       CHAR(1) NOT NULL DEFAULT '0',
    VER_NO         INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_HR_SOC_CHG_NO (CHG_NO),
    KEY IDX_HR_SOC_CHG (EMP_ID, CHG_TYPE, STATUS)
) COMMENT='社保增减员/转移单（FR-4.7-1-6 增员 / FR-4.7-4-15 减员）';

-- ---------- 2. 调岗/转正加列 ----------

ALTER TABLE hr_transfer
    ADD COLUMN PLACE_CHANGE CHAR(1) NULL COMMENT '1=变更社保缴纳地（HR 审核环节勾选）' AFTER BAND_NOTE,
    ADD COLUMN NEW_PLACE VARCHAR(64) NULL COMMENT '新社保缴纳地（值域=社保政策表城市）' AFTER PLACE_CHANGE,
    ADD COLUMN NEW_SALARY DECIMAL(12,2) NULL COMMENT '调薪后月薪（转正单可选，审批过写回主档）' AFTER NEW_PLACE;

-- ---------- 3. 离职加列 ----------

ALTER TABLE hr_exit
    ADD COLUMN SETTLE_ECOMP DECIMAL(12,2) NULL COMMENT '经济补偿金（N 法定口径自动试算，HR 可覆盖；区别于 SETTLE_COMP 调休折算）' AFTER SETTLE_NONCOMP,
    ADD COLUMN SOURCE VARCHAR(8) NOT NULL DEFAULT 'HR' COMMENT '申请来源 SELF 自助端/HR 代录' AFTER REASON,
    ADD COLUMN RETAIN_NOTE VARCHAR(500) NULL COMMENT '挽留意见（审批人填写，非阻断留痕）' AFTER SOURCE,
    ADD COLUMN REDUCE_WAIVED CHAR(1) NOT NULL DEFAULT '0' COMMENT '1=社保减员手工豁免（离场凭证放行）' AFTER REDUCE_NOTE,
    ADD COLUMN REDUCE_WAIVE_NOTE VARCHAR(200) NULL COMMENT '豁免原因+豁免人留痕' AFTER REDUCE_WAIVED;

-- ---------- 4. 薪资明细加列：减员标注（payroll-calculation 衔接） ----------

ALTER TABLE hr_payroll_run_item
    ADD COLUMN SOCIAL_NOTE VARCHAR(50) NULL COMMENT '社保备注（如 已减员，social-insurance-ops 衔接）' AFTER PROVIDENT_EE;
