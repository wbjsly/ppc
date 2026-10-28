-- ============================================================
-- 181-po-collaboration-console.sql
-- change: add-po-collaboration-console（M12.5 订单协同操作台）
--   ① erp_po_coop_ticket 协同异常工单表（design D1：
--     spec R3"在工单中解锁"L5 留痕原无载体——lockIfNeeded 只写
--     ACT_LOCK 日志+coopLock='1'，解锁走页面按钮无工单生命周期；
--     新表承载 OPEN→DONE 闭环，只 INSERT + 状态 UPDATE）
--   ② erp_proc_po_coop 补 QTY_SUGGESTED/QTY_REASON 两列（design D2：
--     数量变更建议结构化——原 confirm 的 qtyNote 只是自由文本拼 DETAIL）
-- ============================================================
-- 幂等：CREATE TABLE IF NOT EXISTS + information_schema 判存在 PREPARE（沿 178/179 惯例）
-- ============================================================

-- ---------- 1. 协同异常工单表（OPEN/DONE 状态机 + L5 解锁留痕） ----------

CREATE TABLE IF NOT EXISTS erp_po_coop_ticket (
    ID             VARCHAR(64)  NOT NULL,
    TICKET_NO      VARCHAR(32)  NOT NULL COMMENT '工单号 PCT+yyyyMMdd+序号',
    SUPPLIER_ID    VARCHAR(64)  NOT NULL,
    SUPPLIER_NAME  VARCHAR(128) DEFAULT NULL,
    LOCK_PO_NOS    VARCHAR(500) NOT NULL COMMENT '连续超时的 PO 单号清单（JSON 数组或顿号分隔）',
    LOCK_AT        DATETIME     NOT NULL COMMENT '锁定触发时间',
    STATUS         VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / DONE',
    UNLOCK_BY      VARCHAR(64)  DEFAULT NULL COMMENT '解锁人（L5 留痕）',
    UNLOCK_AT      DATETIME     DEFAULT NULL,
    UNLOCK_REASON  VARCHAR(500) DEFAULT NULL COMMENT '解锁原因（L5 留痕，resolve 必填）',
    CREATE_BY      VARCHAR(64)  DEFAULT NULL,
    CREATE_DATE    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY      VARCHAR(64)  DEFAULT NULL,
    UPDATE_DATE    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG       VARCHAR(1)   NOT NULL DEFAULT '0',
    VER_NO         INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_PO_COOP_TK_NO (TICKET_NO),
    KEY IDX_PO_COOP_TK_SUP (SUPPLIER_ID, STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='PO 协同异常工单（连续 3 张超时锁定→工单→解锁 L5 留痕，spec po-collaboration）';

-- ---------- 2. erp_proc_po_coop 数量建议两列（结构化，design D2） ----------

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'erp_proc_po_coop'
                   AND COLUMN_NAME = 'QTY_SUGGESTED');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE erp_proc_po_coop ADD COLUMN QTY_SUGGESTED DECIMAL(18,3) DEFAULT NULL COMMENT ''供应商建议数量（结构化数量变更建议，不写回 PO）''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'erp_proc_po_coop'
                   AND COLUMN_NAME = 'QTY_REASON');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE erp_proc_po_coop ADD COLUMN QTY_REASON VARCHAR(255) DEFAULT NULL COMMENT ''数量建议原因''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
