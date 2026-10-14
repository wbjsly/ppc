-- ============================================================
-- 130-qms-ncr-disposition.sql
-- change: rework-ncr-identification-disposition（6.5 不合格品标识隔离与处置跟踪）
--         spec ncr-management：ADDED 隔离确认与标识卡 / 处置跟踪多态视图 /
--         CTQ 处置质量经理审批；MODIFIED 处置执行确认（结构化 + 成本）
-- 顺序：erp_qms_ncr 追加 14 列
--   隔离 4：isolated_flag / isolate_bin / isolated_by / isolated_time（design D1）
--   挑选 4：sort_total_qty / sort_ok_qty / sort_ng_qty / sort_hours（design D3）
--   返工 4：rework_steps / rework_params / rework_check_req / rework_ctq_confirmed
--   成本 1：dispose_cost_amount（COPQ 内部失败归集额）
--   审批 1：dispo_pending_flag（CTQ 处置待审批阶段标识，design D2——非状态机新状态）
-- 幂等：ADD COLUMN 前 information_schema 判断（可连跑多遍）
-- ============================================================

-- ---------- 隔离确认四列（design D1：日志 + 冗余列，不建隔离单） ----------
--     隔离确认不改状态机流转（spec：隔离确认 MUST NOT 改变 NCR 状态机）。
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_qms_ncr'
               AND COLUMN_NAME = 'ISOLATED_FLAG');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_qms_ncr ADD COLUMN ISOLATED_FLAG VARCHAR(1) DEFAULT NULL COMMENT ''1=已隔离确认'' AFTER FROZEN_FLAG',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_qms_ncr'
               AND COLUMN_NAME = 'ISOLATE_BIN');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_qms_ncr ADD COLUMN ISOLATE_BIN VARCHAR(64) DEFAULT NULL COMMENT ''隔离区货位'' AFTER ISOLATED_FLAG',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_qms_ncr'
               AND COLUMN_NAME = 'ISOLATED_BY');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_qms_ncr ADD COLUMN ISOLATED_BY VARCHAR(64) DEFAULT NULL COMMENT ''贴标人（隔离确认人）'' AFTER ISOLATE_BIN',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_qms_ncr'
               AND COLUMN_NAME = 'ISOLATED_TIME');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_qms_ncr ADD COLUMN ISOLATED_TIME DATETIME DEFAULT NULL COMMENT ''隔离确认时间'' AFTER ISOLATED_BY',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 挑选结果结构化四列（spec 处置执行确认：合格+不合格=总，工时） ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_qms_ncr'
               AND COLUMN_NAME = 'SORT_TOTAL_QTY');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_qms_ncr ADD COLUMN SORT_TOTAL_QTY DECIMAL(18,4) DEFAULT NULL COMMENT ''挑选总数量'' AFTER DISPOSE_RESULT',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_qms_ncr'
               AND COLUMN_NAME = 'SORT_OK_QTY');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_qms_ncr ADD COLUMN SORT_OK_QTY DECIMAL(18,4) DEFAULT NULL COMMENT ''挑选合格数量'' AFTER SORT_TOTAL_QTY',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_qms_ncr'
               AND COLUMN_NAME = 'SORT_NG_QTY');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_qms_ncr ADD COLUMN SORT_NG_QTY DECIMAL(18,4) DEFAULT NULL COMMENT ''挑选不合格数量'' AFTER SORT_OK_QTY',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_qms_ncr'
               AND COLUMN_NAME = 'SORT_HOURS');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_qms_ncr ADD COLUMN SORT_HOURS DECIMAL(10,2) DEFAULT NULL COMMENT ''挑选工时（小时）'' AFTER SORT_NG_QTY',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 返工方案结构化四列（spec：工序/参数/检验要求 + CTQ 全含勾选） ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_qms_ncr'
               AND COLUMN_NAME = 'REWORK_STEPS');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_qms_ncr ADD COLUMN REWORK_STEPS TEXT NULL COMMENT ''返工工序步骤'' AFTER DISPOSE_PLAN',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_qms_ncr'
               AND COLUMN_NAME = 'REWORK_PARAMS');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_qms_ncr ADD COLUMN REWORK_PARAMS TEXT NULL COMMENT ''返工关键工艺参数'' AFTER REWORK_STEPS',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_qms_ncr'
               AND COLUMN_NAME = 'REWORK_CHECK_REQ');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_qms_ncr ADD COLUMN REWORK_CHECK_REQ TEXT NULL COMMENT ''返工后检验要求（至少含原检验标准全部 CTQ 项）'' AFTER REWORK_PARAMS',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_qms_ncr'
               AND COLUMN_NAME = 'REWORK_CTQ_CONFIRMED');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_qms_ncr ADD COLUMN REWORK_CTQ_CONFIRMED VARCHAR(1) DEFAULT NULL COMMENT ''1=已勾选确认返工检验标准含原 CTQ 全部项'' AFTER REWORK_CHECK_REQ',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 处置成本（spec：确认时可选录入，>0 同事务归集 COPQ 内部失败） ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_qms_ncr'
               AND COLUMN_NAME = 'DISPOSE_COST_AMOUNT');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_qms_ncr ADD COLUMN DISPOSE_COST_AMOUNT DECIMAL(18,2) DEFAULT NULL COMMENT ''处置成本（COPQ 内部失败归集额）'' AFTER SORT_HOURS',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- CTQ 处置待审批阶段标识（design D2：非状态，评审字段暂存） ----------
--     '1' = CTQ 单评审已提交、待质量经理审批（ApprovalEngine bizType=NcrDisposition）；
--     通过 → 流转段执行；驳回 → 清标识与处置选择可改选。
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_qms_ncr'
               AND COLUMN_NAME = 'DISPO_PENDING_FLAG');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_qms_ncr ADD COLUMN DISPO_PENDING_FLAG VARCHAR(1) DEFAULT NULL COMMENT ''1=CTQ 处置待质量经理审批'' AFTER REVIEW_OPINION',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 二次 NCR 关联（rework-ncr-control design D4：复检仍不合格上调） ----------
--     指向触发本单的前序 NCR（同批复检链），为空 = 首次不合格。
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_qms_ncr'
               AND COLUMN_NAME = 'REF_NCR_ID');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_qms_ncr ADD COLUMN REF_NCR_ID VARCHAR(64) DEFAULT NULL COMMENT ''前序 NCR ID（二次不合格关联，首单为空）'' AFTER REF_ID',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 追溯单来源 NCR（rework-ncr-control D5：漏检召回评估双向关联） ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_inv_trace_order'
               AND COLUMN_NAME = 'SOURCE_NCR_ID');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_inv_trace_order ADD COLUMN SOURCE_NCR_ID VARCHAR(64) DEFAULT NULL COMMENT ''来源 NCR ID（漏检召回评估入口）'' AFTER DEFECT_REASON',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
