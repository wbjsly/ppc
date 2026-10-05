-- 036 比价矩阵（2.2.4，change add-price-comparison-matrix，design D2/D4）
--   1) erp_proc_analysis_snapshot：比价结果快照（BR-4.2-14 双轨 + 审计留痕，RFQ 维度唯一、重复定标覆盖）
--   2) erp_proc_rfq.WEIGHT_QUALITY：四维权重之质量维（占位期服务端强制 0，design D2）
-- 幂等：information_schema + PREPARE（033/034/035 范式）

-- 1) 快照表
CREATE TABLE IF NOT EXISTS erp_proc_analysis_snapshot (
    ID VARCHAR(64) NOT NULL,
    RFQ_ID VARCHAR(64) NOT NULL,
    RFQ_NO VARCHAR(32),
    -- 快照 JSON（schemaVersion 自描述：四维权重含锁定质量维、全部报价原始/谈判双轨价、
    --            异常确认与剔除记录及操作人时间、均值与偏离率、analysisNo/结论、中选供应商与成交价）
    SNAPSHOT_JSON MEDIUMTEXT NOT NULL,
    -- 冗余检索列（列表页直接展示，免解析 JSON）
    ANALYSIS_NO VARCHAR(64),
    AWARD_SUPPLIER_ID VARCHAR(64),
    AWARD_PRICE DECIMAL(18,4),
    CREATE_BY VARCHAR(64),
    CREATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY VARCHAR(64),
    UPDATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0',
    VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    -- RFQ 维度唯一：重复定标覆盖更新（design D4）
    UNIQUE KEY UK_ANALYSIS_RFQ (RFQ_ID),
    KEY IDX_ANALYSIS_NO (ANALYSIS_NO)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 2) RFQ 四维权重之质量维（占位期默认 0，服务端锁定）
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_proc_rfq'
        AND COLUMN_NAME = 'WEIGHT_QUALITY') = 0,
    'ALTER TABLE erp_proc_rfq
       ADD COLUMN WEIGHT_QUALITY INT NOT NULL DEFAULT 0 COMMENT ''质量维权重（占位期强制 0，待 2.5 接入解锁）''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
