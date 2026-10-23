-- ============================================================
-- 146 外币期末重估（change add-fx-revaluation，spec fx-revaluation）
-- 内容：①重估台账 erp_fin_fx_reval ②6604 汇兑损益科目
--       ③AP/AR 发票加 BOOKING_RATE（AR 加 CURRENCY 回填 CNY）
--       ④银行账户加 FC_BALANCE/BOOKING_RATE ⑤M8-5 菜单 PERM 回填
-- 幂等：IF NOT EXISTS / INSERT IGNORE / INFORMATION_SCHEMA 判存
-- ============================================================

-- ---------- 1. 重估台账（design D5：逐笔粒度与凭证行对应） ----------
CREATE TABLE IF NOT EXISTS erp_fin_fx_reval (
    ID VARCHAR(64) NOT NULL COMMENT '主键',
    REVAL_NO VARCHAR(40) NOT NULL COMMENT '重估单号 REVAL-YYYYMM-序',
    PERIOD CHAR(6) NOT NULL COMMENT '重估期间 YYYYMM',
    ITEM_TYPE VARCHAR(8) NOT NULL COMMENT '项目类型 AP/BANK/AR',
    ITEM_ID VARCHAR(64) NOT NULL COMMENT '项目 ID（发票/账户）',
    ITEM_NO VARCHAR(64) DEFAULT NULL COMMENT '项目单号（发票号/账号）',
    PARTY_ID VARCHAR(64) DEFAULT NULL COMMENT '往来对象（供应商/客户）',
    CURRENCY CHAR(3) NOT NULL COMMENT '币种（ISO 4217）',
    FC_AMOUNT DECIMAL(18,2) NOT NULL COMMENT '外币未清额',
    BOOKING_RATE DECIMAL(18,6) NOT NULL COMMENT '原记账汇率（6 位）',
    PERIOD_RATE DECIMAL(18,6) NOT NULL COMMENT '期末汇率（6 位）',
    DIFF_AMOUNT DECIMAL(18,2) NOT NULL COMMENT '汇兑差额（正=收益 负=损失，2 位）',
    VOUCHER_ID VARCHAR(64) DEFAULT NULL COMMENT '重估凭证 ID',
    REVERSED_BY VARCHAR(64) DEFAULT NULL COMMENT '被冲回凭证 ID（红冲链）',
    CREATE_BY VARCHAR(64) DEFAULT NULL,
    CREATE_DATE DATETIME DEFAULT NULL,
    UPDATE_BY VARCHAR(64) DEFAULT NULL,
    UPDATE_DATE DATETIME DEFAULT NULL,
    DEL_FLAG CHAR(1) NOT NULL DEFAULT '0',
    VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    -- 批次号为期间级编号（同期多行共用），唯一约束落在「批次+项目」防同批重复行（task 4.4 修正）
    UNIQUE KEY UK_FIN_FX_REVAL_ITEM (REVAL_NO, ITEM_TYPE, ITEM_ID),
    KEY IDX_FIN_FX_REVAL_PERIOD (PERIOD, ITEM_TYPE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外币重估台账（add-fx-revaluation，spec fx-revaluation）';

-- ---------- 2. 6604 汇兑损益科目（design D8：6603 被租赁利息占用） ----------
INSERT IGNORE INTO erp_fin_account (ID, ACCOUNT_CODE, ACCOUNT_NAME, DIRECTION, CATEGORY, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
SELECT 'FIN-ACCT-6604', '6604', '汇兑损益', 'CREDIT', '损益', 'ACTIVE', 'system', NOW(), '0', 0
WHERE NOT EXISTS (SELECT 1 FROM erp_fin_account WHERE ACCOUNT_CODE = '6604');

-- ---------- 3. AP 发票加 BOOKING_RATE（design D1：外币按发票日中间价固化） ----------
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_fin_ap_invoice' AND COLUMN_NAME = 'BOOKING_RATE');
SET @s := IF(@c = 0, 'ALTER TABLE erp_fin_ap_invoice ADD COLUMN BOOKING_RATE DECIMAL(18,6) NOT NULL DEFAULT 1 COMMENT ''原记账汇率（外币按发票日中间价固化，CNY=1）''', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------- 4. AR 发票加 CURRENCY + BOOKING_RATE（存量回填 CNY/1） ----------
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_fin_ar_invoice' AND COLUMN_NAME = 'CURRENCY');
SET @s := IF(@c = 0, 'ALTER TABLE erp_fin_ar_invoice ADD COLUMN CURRENCY CHAR(3) NOT NULL DEFAULT ''CNY'' COMMENT ''币种（ISO 4217，默认 CNY）''', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_fin_ar_invoice' AND COLUMN_NAME = 'BOOKING_RATE');
SET @s := IF(@c = 0, 'ALTER TABLE erp_fin_ar_invoice ADD COLUMN BOOKING_RATE DECIMAL(18,6) NOT NULL DEFAULT 1 COMMENT ''原记账汇率（外币按开票日中间价固化，CNY=1）''', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------- 5. 银行账户加 FC_BALANCE + BOOKING_RATE（design D2 三列并存） ----------
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_fin_bank_account' AND COLUMN_NAME = 'FC_BALANCE');
SET @s := IF(@c = 0, 'ALTER TABLE erp_fin_bank_account ADD COLUMN FC_BALANCE DECIMAL(18,2) DEFAULT NULL COMMENT ''外币余额（CNY 账户恒等于 BALANCE）''', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_fin_bank_account' AND COLUMN_NAME = 'BOOKING_RATE');
SET @s := IF(@c = 0, 'ALTER TABLE erp_fin_bank_account ADD COLUMN BOOKING_RATE DECIMAL(18,6) DEFAULT NULL COMMENT ''记账汇率（外币账户开户日中间价，CNY=1）''', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- CNY 账户回填：FC_BALANCE=BALANCE、BOOKING_RATE=1（外币账户存量为空须人工核对，当前库存无外币账户）
UPDATE erp_fin_bank_account SET FC_BALANCE = BALANCE, BOOKING_RATE = 1
WHERE CURRENCY = 'CNY' AND (FC_BALANCE IS NULL OR BOOKING_RATE IS NULL);

-- ---------- 6. M8-5 菜单 PERM 回填（与 M8-1~4 对齐：财务资产/财务经理/管理员） ----------
-- 任务 2.3 扩展：SO 加交易币种，应收生成链路读 SO 币种固化记账汇率（决策 A）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_sd_so' AND COLUMN_NAME = 'CURRENCY');
SET @s := IF(@c = 0, 'ALTER TABLE erp_sd_so ADD COLUMN CURRENCY CHAR(3) NOT NULL DEFAULT ''CNY'' COMMENT ''交易币种（ISO 4217，默认 CNY；应收生成据此固化记账汇率）''', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
UPDATE erp_admin_menu SET PERM = 'ROLE_FIN_ASSET,ROLE_FIN_MGR,ROLE_ADMIN'
WHERE ID IN ('M8-5', 'M8-5-1', 'M8-5-2', 'M8-5-3') AND (PERM IS NULL OR PERM = '');
