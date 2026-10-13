-- ============================================================
-- 128-qms-inspection-tasks.sql
-- change: rework-inspection-tasks（6.2 检验任务，spec inspection-lot ADDED
--         + customer-quality-agreement + completion-backflush / inbound-workbench
--         / sales-shipment ADDED）
-- 顺序：1) 客户质量协议两表（协议 + 投诉留痕）  2) 合格证表
--       3) 检验批补列（ASSIGN_LOG / AGREE_CODE）  4) 参数 OQC_ENFORCE
-- 幂等：全部 IF NOT EXISTS / INSERT IGNORE（可连跑多遍）
-- ============================================================

-- ---------- 1) 客户质量协议（design D4，spec customer-quality-agreement） ----------
--     客户 × 物料/品类 维度；TIGHT_CTQ_JSON 存加严 CTQ 清单；
--     COMPLAINT_STREAK 连续投诉计数（≥2 → 该客户 OQC 自动全检，手工登记/清零留痕）。
CREATE TABLE IF NOT EXISTS erp_qms_customer_agreement (
    ID VARCHAR(64) NOT NULL,
    -- C + yyyyMMdd + - + 6 位流水
    AGREE_CODE VARCHAR(32) NOT NULL COMMENT '协议编码（唯一）',
    CUSTOMER_ID VARCHAR(64) NOT NULL COMMENT '客户 ID',
    CUSTOMER_NAME VARCHAR(128) DEFAULT NULL COMMENT '客户名称（快照）',
    -- ITEM / CATEGORY（二选一必填）
    SCOPE_TYPE VARCHAR(16) NOT NULL COMMENT '适用对象类型 ITEM/CATEGORY',
    SCOPE_VALUE VARCHAR(64) NOT NULL COMMENT '物料编码或品类编码',
    -- [{name,specType,lower,upper,unit,method}] —— 命中时并入检验项（CTQ 必检）
    TIGHT_CTQ_JSON TEXT COMMENT '加严 CTQ 清单 JSON',
    -- NULL = 不覆盖；非空时与风险等级档位取严
    SAMPLE_LEVEL_OVERRIDE VARCHAR(8) DEFAULT NULL COMMENT '抽样档位覆盖 A/B/C',
    -- ≥2 → 该客户 OQC 批自动 100% 全检（D-2：投诉域未建，质量工程师手工登记）
    COMPLAINT_STREAK INT NOT NULL DEFAULT 0 COMMENT '连续投诉计数',
    EFFECTIVE_FROM DATE DEFAULT NULL COMMENT '生效起',
    EFFECTIVE_TO DATE DEFAULT NULL COMMENT '生效止',
    -- DRAFT / ACTIVE / EXPIRED / STOPPED（停用仅阻止新批命中，历史快照不变）
    STATUS VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '状态',
    REMARK VARCHAR(500) DEFAULT NULL,
    CREATE_BY VARCHAR(64) DEFAULT NULL,
    CREATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY VARCHAR(64) DEFAULT NULL,
    UPDATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0',
    VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_QMS_AGREE_CODE (AGREE_CODE),
    KEY IDX_QMS_AGREE_CUST (CUSTOMER_ID),
    KEY IDX_QMS_AGREE_SCOPE (SCOPE_TYPE, SCOPE_VALUE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------- 1b) 协议投诉登记/清零留痕 ----------
CREATE TABLE IF NOT EXISTS erp_qms_agreement_log (
    ID VARCHAR(64) NOT NULL,
    AGREEMENT_ID VARCHAR(64) NOT NULL COMMENT '协议 ID',
    -- COMPLAINT（计数 +1）/ CLEAR（清零）
    ACTION VARCHAR(16) NOT NULL COMMENT '动作',
    STREAK_AFTER INT NOT NULL DEFAULT 0 COMMENT '动作后计数值',
    REASON VARCHAR(255) DEFAULT NULL COMMENT '原因（投诉登记必填）',
    CREATE_BY VARCHAR(64) DEFAULT NULL,
    CREATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY VARCHAR(64) DEFAULT NULL,
    UPDATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0',
    VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_QMS_ALOG_AGREE (AGREEMENT_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------- 2) 检验合格证（OQC 放行同事务生成，spec inspection-lot 合格证） ----------
--     D-3 偏差：无防伪编号体系，CERT_NO 仅流水单号。
CREATE TABLE IF NOT EXISTS erp_qms_certificate (
    ID VARCHAR(64) NOT NULL,
    -- C + yyyyMMdd + - + 6 位流水
    CERT_NO VARCHAR(32) NOT NULL COMMENT '合格证号',
    LOT_NO VARCHAR(32) NOT NULL COMMENT '来源检验批号',
    LOT_ID VARCHAR(64) NOT NULL COMMENT '来源检验批 ID',
    ITEM_CODE VARCHAR(64) NOT NULL COMMENT '物料编码',
    ITEM_NAME VARCHAR(128) DEFAULT NULL,
    QTY DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '合格数量',
    CUSTOMER_NAME VARCHAR(128) DEFAULT NULL COMMENT '客户（发货关联，无则空）',
    RESULT VARCHAR(8) NOT NULL DEFAULT 'PASS' COMMENT '结论',
    ISSUED_BY VARCHAR(64) DEFAULT NULL COMMENT '签发人',
    ISSUED_DATE DATETIME DEFAULT NULL COMMENT '签发时间',
    CREATE_BY VARCHAR(64) DEFAULT NULL,
    CREATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY VARCHAR(64) DEFAULT NULL,
    UPDATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0',
    VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_QMS_CERT_NO (CERT_NO),
    KEY IDX_QMS_CERT_LOT (LOT_NO)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------- 3) 检验批补列（分派留痕 / 协议快照） ----------
--     ASSIGN_LOG：JSON 追加 [{ts,action:ASSIGN|REASSIGN,from,to,reason}]（design D2）
--     AGREE_CODE：命中协议快照（design D4）
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_qms_inspection_lot'
               AND COLUMN_NAME = 'ASSIGN_LOG');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_qms_inspection_lot ADD COLUMN ASSIGN_LOG TEXT COMMENT ''分派/改派留痕 JSON'' AFTER OVERDUE_FLAG',
    'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_qms_inspection_lot'
               AND COLUMN_NAME = 'AGREE_CODE');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_qms_inspection_lot ADD COLUMN AGREE_CODE VARCHAR(64) DEFAULT NULL COMMENT ''命中客户协议编码快照'' AFTER APPLICABILITY_BASIS',
    'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 4) 参数：发货确认 OQC 前置校验开关（默认 ON，OFF 降级黄提示） ----------
--     与 FAI_ENFORCE / IPQC_ENFORCE 同惯例；存量出库回归显式置 OFF 处理。
INSERT IGNORE INTO erp_sys_param
(ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('param-oqc-enforce', 'OQC_ENFORCE', 'ON', 'STRING', 'QMS',
 '发货确认 OQC 前置校验开关（ON=无已放行 OQC 批阻断发货；OFF=降级黄提示，spec sales-shipment 发货确认 OQC 前置校验）', 'seed', '0', 0);
