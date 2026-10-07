-- ============================================================================
-- 058 事件幂等键作用域收窄（add-sales-lead-to-cash tasks 15.3）
--     问题：erp_ops_outbox 幂等键 UK_OB_IDEMPOTENCY(IDEMPOTENCY_KEY) 全局唯一，
--           而键格式为 bizCode:vN —— 同一业务单据的不同事件类型（如发货单的
--           AR.CONFIRMED 与 SHIP.COMPLETED、SO 的 SO.CONFIRMED 与 CREDIT.FROZEN）
--           共享同一键 → 后发事件被 409 拒绝，跨域事件链路断裂。
--     修复：唯一作用域收窄为 (EVENT_TYPE, IDEMPOTENCY_KEY) —— 同类型同版本重放
--           仍拒绝（C-0-06 幂等语义不变），跨类型互不干扰；键格式不变，
--           事件流检索与消费方无感。
-- ============================================================================

-- 幂等：仅当旧的单列唯一键存在且复合键不存在时重建
SET @has_old = (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'erp_ops_outbox'
                   AND INDEX_NAME = 'UK_OB_IDEMPOTENCY');
SET @has_new = (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'erp_ops_outbox'
                   AND INDEX_NAME = 'UK_OB_TYPE_IDEM');
SET @ddl = IF(@has_old > 0 AND @has_new = 0,
    'ALTER TABLE erp_ops_outbox DROP INDEX UK_OB_IDEMPOTENCY, ADD UNIQUE KEY UK_OB_TYPE_IDEM (EVENT_TYPE, IDEMPOTENCY_KEY)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
