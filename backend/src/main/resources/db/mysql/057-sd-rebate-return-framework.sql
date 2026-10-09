-- ============================================================================
-- 057 返利三配置与结算（add-sales-lead-to-cash tasks 11.1，spec sales-rebate）
--     本文件同时规划为退货（第 12 组）与框架协议（第 13 组）的迁移载体，
--     后续组在文件末尾追加，全部语句幂等（可重复执行）。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. 季度销售目标（客户 × 季度）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS erp_sd_rebate_target (
    ID              VARCHAR(64)  NOT NULL,
    CUSTOMER_ID     VARCHAR(64)  NOT NULL,
    CUSTOMER_CODE   VARCHAR(32)  NULL,
    CUSTOMER_NAME   VARCHAR(128) NULL,
    QUARTER         VARCHAR(8)   NOT NULL COMMENT '如 2026Q4',
    TARGET_AMT      DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '季度销售目标（元）',
    REMARK          VARCHAR(500) NULL,
    CREATE_BY       VARCHAR(64)  NULL,
    CREATE_DATE     DATETIME     NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY       VARCHAR(64)  NULL,
    UPDATE_DATE     DATETIME     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG        VARCHAR(1)   NOT NULL DEFAULT '0',
    VER_NO          INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY uk_rb_target (CUSTOMER_ID, QUARTER)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='返利配置-季度销售目标';

-- ----------------------------------------------------------------------------
-- 2. 返利政策阶梯（达成率区间 → 返利率，超额累进；CUSTOMER_ID 为空 = 全局默认政策）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS erp_sd_rebate_policy (
    ID              VARCHAR(64)  NOT NULL,
    POLICY_NO       VARCHAR(32)  NOT NULL COMMENT '政策编号（同政策多阶梯共享）',
    POLICY_NAME     VARCHAR(128) NULL,
    CUSTOMER_ID     VARCHAR(64)  NULL COMMENT '空 = 全局默认政策',
    BAND_FROM       DECIMAL(6,2) NOT NULL COMMENT '达成率下限 %（含）',
    BAND_TO         DECIMAL(6,2) NULL COMMENT '达成率上限 %（不含，空 = 无上限）',
    REBATE_RATE     DECIMAL(6,2) NOT NULL COMMENT '该段返利率 %',
    VALID_FROM      DATE         NULL,
    VALID_TO        DATE         NULL,
    STATUS          VARCHAR(16)  NOT NULL DEFAULT 'EFFECTIVE' COMMENT 'EFFECTIVE/DISABLED',
    SEQ             INT          NOT NULL DEFAULT 0,
    REMARK          VARCHAR(500) NULL,
    CREATE_BY       VARCHAR(64)  NULL,
    CREATE_DATE     DATETIME     NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY       VARCHAR(64)  NULL,
    UPDATE_DATE     DATETIME     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG        VARCHAR(1)   NOT NULL DEFAULT '0',
    VER_NO          INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY idx_rb_policy_cust (CUSTOMER_ID, STATUS),
    KEY idx_rb_policy_no (POLICY_NO)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='返利配置-政策阶梯（超额累进）';

-- ----------------------------------------------------------------------------
-- 3. 年度返利预算（年度总额 + 按季度分解；余额由结算单实时累计）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS erp_sd_rebate_budget (
    ID              VARCHAR(64)  NOT NULL,
    BUDGET_YEAR     INT          NOT NULL COMMENT '预算年度，如 2026',
    TOTAL_AMT       DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '年度返利预算总额',
    Q1_AMT          DECIMAL(18,2) NOT NULL DEFAULT 0,
    Q2_AMT          DECIMAL(18,2) NOT NULL DEFAULT 0,
    Q3_AMT          DECIMAL(18,2) NOT NULL DEFAULT 0,
    Q4_AMT          DECIMAL(18,2) NOT NULL DEFAULT 0,
    REMARK          VARCHAR(500) NULL,
    CREATE_BY       VARCHAR(64)  NULL,
    CREATE_DATE     DATETIME     NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY       VARCHAR(64)  NULL,
    UPDATE_DATE     DATETIME     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG        VARCHAR(1)   NOT NULL DEFAULT '0',
    VER_NO          INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY uk_rb_budget_year (BUDGET_YEAR)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='返利配置-年度返利预算（按季分解）';

-- ----------------------------------------------------------------------------
-- 4. 返利结算单（达成率 + 超额累进分段明细 + 预算校验 + 审批 + 执行）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS erp_sd_rebate_settlement (
    ID              VARCHAR(64)  NOT NULL,
    SETTLE_NO       VARCHAR(32)  NOT NULL COMMENT '结算单号 RB-YYYYMMDD-NNNN',
    CUSTOMER_ID     VARCHAR(64)  NOT NULL,
    CUSTOMER_CODE   VARCHAR(32)  NULL,
    CUSTOMER_NAME   VARCHAR(128) NULL,
    QUARTER         VARCHAR(8)   NOT NULL COMMENT '如 2026Q4',
    TARGET_AMT      DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '季度目标',
    BASE_AMT        DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '返利基数 = 开票确认额 − 退货退款额',
    ACHIEVE_RATE    DECIMAL(8,4) NOT NULL DEFAULT 0 COMMENT '达成率（小数，0.95 = 95%）',
    REBATE_AMT      DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '超额累进返利金额',
    BUDGET_AMT      DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '季度预算额度',
    BUDGET_REMAIN   DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '计算时点季度预算余额',
    OVER_BUDGET     VARCHAR(1)   NOT NULL DEFAULT '0' COMMENT '1 = 超季度预算（C-4.3-05 升级）',
    OVER_REASON     VARCHAR(500) NULL COMMENT '超预算原因说明（必填）',
    BALANCE_PLAN    VARCHAR(500) NULL COMMENT '年度预算平衡方案（超预算必填）',
    SEGMENTS        TEXT         NULL COMMENT '超额累进分段明细 JSON',
    STATUS          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT'
                    COMMENT 'DRAFT/APPROVING/APPROVED/REJECTED/EXECUTED',
    APPROVAL_ID     VARCHAR(64)  NULL,
    PENDING_DATA    VARCHAR(1)   NOT NULL DEFAULT '0' COMMENT '1 = 数据不完整待补齐',
    PENDING_HINT    VARCHAR(500) NULL,
    EXEC_TYPE       VARCHAR(16)  NULL COMMENT 'OFFSET 应付冲抵 / CASH 现金兑现',
    EXEC_AT         DATETIME     NULL,
    EXEC_BY         VARCHAR(64)  NULL,
    CONFIRM_BY      VARCHAR(128) NULL COMMENT '客户确认方式留痕',
    CONFIRM_NOTE    VARCHAR(500) NULL,
    OFFSET_AMT      DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '已冲抵应收金额',
    CASH_AMT        DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '已现金兑现金额',
    VOUCHER_ID      VARCHAR(64)  NULL COMMENT '结算凭证（借 6601 / 贷 1122 或 1002）',
    VOUCHER_NO      VARCHAR(32)  NULL,
    REMARK          VARCHAR(500) NULL,
    CREATE_BY       VARCHAR(64)  NULL,
    CREATE_DATE     DATETIME     NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY       VARCHAR(64)  NULL,
    UPDATE_DATE     DATETIME     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG        VARCHAR(1)   NOT NULL DEFAULT '0',
    VER_NO          INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY uk_rb_settle_no (SETTLE_NO),
    UNIQUE KEY uk_rb_settle_cust_q (CUSTOMER_ID, QUARTER),
    KEY idx_rb_settle_status (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='返利结算单（计算→审批→执行→凭证）';

-- ----------------------------------------------------------------------------
-- 5. 销售费用科目（返利结算凭证借方；6601）
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO erp_fin_account (ID, ACCOUNT_CODE, ACCOUNT_NAME, DIRECTION, CATEGORY, STATUS, DEL_FLAG, VER_NO)
SELECT 'acc-6601', '6601', '销售费用', 'DR', '损益', 'ACTIVE', '0', 0
WHERE NOT EXISTS (SELECT 1 FROM erp_fin_account WHERE ACCOUNT_CODE = '6601');

-- ============================================================================
-- 12. 销售退货（add-sales-lead-to-cash tasks 12.1，spec sales-return，D14）
--     erp_sd_return / erp_sd_return_line 已于 055 提前建，此处追加判定与执行扩展列
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 12.1 退货头：判定（责任方/超期/依据/处理方式）与执行（退款红字/换货/入库）列
-- ----------------------------------------------------------------------------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_sd_return'
        AND COLUMN_NAME = 'JUDGE_BY') = 0,
    'ALTER TABLE erp_sd_return
       ADD COLUMN JUDGE_BY VARCHAR(64) DEFAULT NULL COMMENT ''判定人（12.3）'' AFTER REMARK,
       ADD COLUMN JUDGE_AT DATETIME DEFAULT NULL COMMENT ''判定时间'' AFTER JUDGE_BY,
       ADD COLUMN JUDGE_NOTE VARCHAR(500) DEFAULT NULL COMMENT ''判定依据'' AFTER JUDGE_AT,
       ADD COLUMN LIABILITY VARCHAR(32) DEFAULT NULL COMMENT ''责任方 OUR_QUALITY/CUSTOMER/LOGISTICS'' AFTER JUDGE_NOTE,
       ADD COLUMN OVERDUE VARCHAR(1) NOT NULL DEFAULT ''0'' COMMENT ''超期标记'' AFTER LIABILITY,
       ADD COLUMN OVERDUE_NOTE VARCHAR(500) DEFAULT NULL AFTER OVERDUE,
       ADD COLUMN JUDGE_HANDLE VARCHAR(16) DEFAULT NULL COMMENT ''判定处理方式 REFUND/EXCHANGE'' AFTER OVERDUE_NOTE,
       ADD COLUMN SUBMIT_AT DATETIME DEFAULT NULL AFTER JUDGE_HANDLE,
       ADD COLUMN REFUND_AMT DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT ''累计已退款'' AFTER SUBMIT_AT,
       ADD COLUMN REFUND_AT DATETIME DEFAULT NULL AFTER REFUND_AMT,
       ADD COLUMN REFUND_BY VARCHAR(64) DEFAULT NULL AFTER REFUND_AT,
       ADD COLUMN REFUND_PAY_NO VARCHAR(64) DEFAULT NULL COMMENT ''退款支付登记号'' AFTER REFUND_BY,
       ADD COLUMN RED_INVOICE_ID VARCHAR(64) DEFAULT NULL COMMENT ''红字发票 ID（已开票退款回填）'' AFTER REFUND_PAY_NO,
       ADD COLUMN RED_INVOICE_NO VARCHAR(64) DEFAULT NULL AFTER RED_INVOICE_ID,
       ADD COLUMN VOUCHER_ID VARCHAR(64) DEFAULT NULL COMMENT ''红冲凭证/入库凭证'' AFTER RED_INVOICE_NO,
       ADD COLUMN VOUCHER_NO VARCHAR(32) DEFAULT NULL AFTER VOUCHER_ID,
       ADD COLUMN EXCHANGE_SHIP_ID VARCHAR(64) DEFAULT NULL COMMENT ''换货发货单'' AFTER VOUCHER_NO,
       ADD COLUMN EXCHANGE_SHIP_NO VARCHAR(32) DEFAULT NULL AFTER EXCHANGE_SHIP_ID,
       ADD COLUMN STOCK_IN VARCHAR(1) NOT NULL DEFAULT ''0'' COMMENT ''实物入库完成'' AFTER EXCHANGE_SHIP_NO,
       ADD COLUMN STOCK_IN_AT DATETIME DEFAULT NULL AFTER STOCK_IN,
       ADD COLUMN STOCK_IN_BY VARCHAR(64) DEFAULT NULL AFTER STOCK_IN_AT',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ----------------------------------------------------------------------------
-- 12.1 退货行：核定可退数量与实物入库列
-- ----------------------------------------------------------------------------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_sd_return_line'
        AND COLUMN_NAME = 'JUDGE_QTY') = 0,
    'ALTER TABLE erp_sd_return_line
       ADD COLUMN JUDGE_QTY DECIMAL(18,3) DEFAULT NULL COMMENT ''核定可退数量（NULL=未判定）'' AFTER QTY,
       ADD COLUMN IN_QTY DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT ''已实物入库数量'' AFTER JUDGE_QTY,
       ADD COLUMN STOCK_BATCH_NO VARCHAR(64) DEFAULT NULL COMMENT ''回补批次号'' AFTER IN_QTY,
       ADD COLUMN QC_FLAG VARCHAR(1) NOT NULL DEFAULT ''0'' COMMENT ''1=入待检 QC_QTY（不计 ATP）'' AFTER STOCK_BATCH_NO',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ----------------------------------------------------------------------------
-- 12.1 退货判定记录（判定人/时间/依据全链留痕）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS erp_sd_return_judge_log (
    ID              VARCHAR(64)  NOT NULL,
    RETURN_ID       VARCHAR(64)  NOT NULL,
    RETURN_NO       VARCHAR(32)  NOT NULL,
    -- JUDGE 判定 / JUDGE_REJECT 判定驳回 / SUBMIT 提交审批 / STOCK_IN 实物入库 / REFUND 退款 / EXCHANGE 换货
    OP_TYPE         VARCHAR(32)  NOT NULL,
    LIABILITY       VARCHAR(32)  DEFAULT NULL,
    JUDGE_HANDLE    VARCHAR(16)  DEFAULT NULL,
    OVERDUE         VARCHAR(1)   DEFAULT NULL,
    JUDGE_QTY       DECIMAL(18,3) DEFAULT NULL,
    BASIS           VARCHAR(500) DEFAULT NULL COMMENT '判定依据/操作说明',
    DETAIL          TEXT         NULL COMMENT '行级明细 JSON',
    OP_BY           VARCHAR(64)  DEFAULT NULL,
    OP_AT           DATETIME     DEFAULT CURRENT_TIMESTAMP,
    CREATE_BY       VARCHAR(64)  DEFAULT NULL,
    CREATE_DATE     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY       VARCHAR(64)  DEFAULT NULL,
    UPDATE_DATE     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG        VARCHAR(1)   NOT NULL DEFAULT '0',
    VER_NO          INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_SDRJ_RT (RETURN_ID, OP_TYPE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售退货判定记录（12.1/12.3 判定留痕）';

-- ----------------------------------------------------------------------------
-- 12.7 退货入库凭证贷方科目：主营业务成本
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO erp_fin_account (ID, ACCOUNT_CODE, ACCOUNT_NAME, DIRECTION, CATEGORY, STATUS, DEL_FLAG, VER_NO)
SELECT 'acc-6401', '6401', '主营业务成本', 'CR', '损益', 'ACTIVE', '0', 0
WHERE NOT EXISTS (SELECT 1 FROM erp_fin_account WHERE ACCOUNT_CODE = '6401');

-- ----------------------------------------------------------------------------
-- 参数：约定退货期（天）——超期判定基准（spec 12.3）
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO erp_sys_param
(ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('param-return-period-days', 'RETURN_PERIOD_DAYS', '30', 'DAYS', 'SD',
 '约定退货期（天），申请超期由判定环节标记超期并附加说明（spec sales-return 12.3）', 'seed', '0', 0);

-- ============================================================================
-- 13. 销售框架协议（add-sales-lead-to-cash tasks 13.1，spec sales-framework-agreement，D11）
--     与销售合同（erp_crm_contract）为两个独立对象；本节三表 + 发货单挂框架列
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 13.1 框架协议头（FW_NO 生成后锁定；生效/失效/终止状态机）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS erp_sd_framework (
    ID              VARCHAR(64)  NOT NULL,
    -- FW + yyyyMMdd + 4 位流水，创建后不可改（spec 3.11.1 编码锁定）
    FW_NO           VARCHAR(32)  NOT NULL COMMENT '框架协议编号（生成后锁定）',
    CUSTOMER_ID     VARCHAR(64)  NOT NULL,
    CUSTOMER_CODE   VARCHAR(32)  DEFAULT NULL,
    CUSTOMER_NAME   VARCHAR(128) DEFAULT NULL,
    TITLE           VARCHAR(255) DEFAULT NULL,
    EFFECTIVE_DATE  DATE         NOT NULL COMMENT '生效日期',
    EXPIRE_DATE     DATE         NOT NULL COMMENT '失效日期（超期不可下达）',
    TOTAL_QTY       DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '协议总量（=Σ行，变更重算）',
    -- EFFECTIVE 生效中 / TERMINATED 已终止（冻结后续发货）/ EXPIRED 过期
    STATUS          VARCHAR(16)  NOT NULL DEFAULT 'EFFECTIVE',
    APPROVAL_ID     VARCHAR(64)  DEFAULT NULL COMMENT '变更/终止审批实例（S-4.3-11 L2）',
    PENDING_CHANGE  TEXT         NULL COMMENT '待审批变更 JSON {type,lines,reason}',
    CHANGE_LOG      TEXT         NULL COMMENT '变更历史 JSON 数组（前后值留痕）',
    TERMINATE_REASON VARCHAR(500) DEFAULT NULL,
    TERMINATE_BY    VARCHAR(64)  DEFAULT NULL,
    TERMINATE_AT    DATETIME     DEFAULT NULL,
    REMARK          VARCHAR(500) DEFAULT NULL,
    CREATE_BY       VARCHAR(64)  DEFAULT NULL,
    CREATE_DATE     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY       VARCHAR(64)  DEFAULT NULL,
    UPDATE_DATE     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG        VARCHAR(1)   NOT NULL DEFAULT '0',
    VER_NO          INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_SD_FW_NO (FW_NO),
    KEY IDX_SD_FW_CUST (CUSTOMER_ID, STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售框架协议（3.11.1，独立于销售合同 D11）';

-- ----------------------------------------------------------------------------
-- 13.1 框架协议行（总量/已下达/已发三量 + 锁定单价）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS erp_sd_framework_line (
    ID              VARCHAR(64)  NOT NULL,
    FRAMEWORK_ID    VARCHAR(64)  NOT NULL,
    LINE_NO         INT          NOT NULL,
    ITEM_CODE       VARCHAR(64)  NOT NULL,
    ITEM_NAME       VARCHAR(255) DEFAULT NULL,
    BASE_UNIT       VARCHAR(16)  DEFAULT NULL,
    TOTAL_QTY       DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '行协议总量',
    RELEASED_QTY    DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '已下达量',
    SHIPPED_QTY     DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '已发量',
    UNIT_PRICE      DECIMAL(18,4) DEFAULT NULL COMMENT '锁定单价（下达/发货带出）',
    WAREHOUSE_CODE  VARCHAR(32)  DEFAULT NULL,
    REMARK          VARCHAR(500) DEFAULT NULL,
    CREATE_BY       VARCHAR(64)  DEFAULT NULL,
    CREATE_DATE     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY       VARCHAR(64)  DEFAULT NULL,
    UPDATE_DATE     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG        VARCHAR(1)   NOT NULL DEFAULT '0',
    VER_NO          INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_SD_FWL_FW (FRAMEWORK_ID, LINE_NO)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='框架协议行（余量三量跟踪 C-4.3-10）';

-- ----------------------------------------------------------------------------
-- 13.1 框架下达单（框架订单：引用协议行、客户、物料、数量与交期）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS erp_sd_framework_release (
    ID              VARCHAR(64)  NOT NULL,
    -- FWRL + yyyyMMdd + 4 位流水
    RELEASE_NO      VARCHAR(32)  NOT NULL COMMENT '框架下达单号',
    FRAMEWORK_ID    VARCHAR(64)  NOT NULL,
    FW_LINE_ID      VARCHAR(64)  NOT NULL,
    LINE_NO         INT          DEFAULT NULL,
    CUSTOMER_ID     VARCHAR(64)  NOT NULL,
    CUSTOMER_CODE   VARCHAR(32)  DEFAULT NULL,
    CUSTOMER_NAME   VARCHAR(128) DEFAULT NULL,
    ITEM_CODE       VARCHAR(64)  NOT NULL,
    ITEM_NAME       VARCHAR(255) DEFAULT NULL,
    QTY             DECIMAL(18,3) NOT NULL COMMENT '下达数量',
    SHIPPED_QTY     DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '本单已发量',
    DELIVER_DATE    DATE         DEFAULT NULL COMMENT '要求交期（分批执行时间表）',
    -- OPEN 未发 / PARTIAL 部分发 / SHIPPED 已发完 / CANCELLED 已取消
    STATUS          VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    LAST_SHIP_ID    VARCHAR(64)  DEFAULT NULL,
    LAST_SHIP_NO    VARCHAR(32)  DEFAULT NULL,
    REMARK          VARCHAR(500) DEFAULT NULL,
    CREATE_BY       VARCHAR(64)  DEFAULT NULL,
    CREATE_DATE     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY       VARCHAR(64)  DEFAULT NULL,
    UPDATE_DATE     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG        VARCHAR(1)   NOT NULL DEFAULT '0',
    VER_NO          INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_SD_FWRL_NO (RELEASE_NO),
    KEY IDX_SD_FWRL_FW (FRAMEWORK_ID, FW_LINE_ID),
    KEY IDX_SD_FWRL_STATUS (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='框架下达单（13.3 下达回写余量、13.6 执行视图）';

-- ----------------------------------------------------------------------------
-- 13.6 发货单挂框架来源（动态列幂等；执行视图回链与超量审计）
-- ----------------------------------------------------------------------------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_sd_shipment'
        AND COLUMN_NAME = 'FRAMEWORK_ID') = 0,
    'ALTER TABLE erp_sd_shipment
       ADD COLUMN FRAMEWORK_ID VARCHAR(64) DEFAULT NULL COMMENT ''来源框架协议'' AFTER RETURN_ID,
       ADD COLUMN RELEASE_ID VARCHAR(64) DEFAULT NULL COMMENT ''来源框架下达单'' AFTER FRAMEWORK_ID,
       ADD COLUMN RELEASE_NO VARCHAR(32) DEFAULT NULL AFTER RELEASE_ID',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ============================================================================
-- 14. 销售合同（add-sales-lead-to-cash tasks 14.1，spec sales-contract，D10/D11）
--     erp_crm_contract / _plan / _version 已于 056 提前建；此处补签订留痕与
--     变更版本审批列（三表结构其余不变）
-- ============================================================================

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_crm_contract'
        AND COLUMN_NAME = 'SIGNED_BY') = 0,
    'ALTER TABLE erp_crm_contract
       ADD COLUMN SIGNED_BY VARCHAR(64) DEFAULT NULL COMMENT ''签订人（14.2）'' AFTER SIGN_DATE,
       ADD COLUMN SIGNED_AT DATETIME DEFAULT NULL COMMENT ''签订时间'' AFTER SIGNED_BY,
       ADD COLUMN CHANGE_COUNT INT NOT NULL DEFAULT 0 COMMENT ''累计变更次数（14.6）'' AFTER VERSION_NO',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_crm_contract_version'
        AND COLUMN_NAME = 'STATUS') = 0,
    'ALTER TABLE erp_crm_contract_version
       ADD COLUMN STATUS VARCHAR(16) NOT NULL DEFAULT ''APPROVED'' COMMENT ''APPROVING/APPROVED/REJECTED'' AFTER OP_TYPE,
       ADD COLUMN APPROVAL_ID VARCHAR(64) DEFAULT NULL AFTER STATUS',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
