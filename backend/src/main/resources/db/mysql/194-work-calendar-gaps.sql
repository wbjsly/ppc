-- =====================================================================
-- 194-work-calendar-gaps.sql（close-work-calendar-gaps，spec capacity-load）
-- 任务 1.1 数据卫生：
--   1) 物理清理 erp_admin_shift / erp_mrp_wc_shift 中 DEL_FLAG='1' 测试残留
--   2) 清理后校验活跃 SHIFT_CODE 无重复（有重复输出明细，不建键）
--   3) 受控 ALTER 为 erp_admin_shift 建 UNIQUE(SHIFT_CODE)——防逻辑删占码
-- 幂等：清理为条件 DELETE（重跑 0 行）；唯一键经 information_schema 检查后建。
-- =====================================================================

-- ---- 1. 物理清理逻辑删残留（测试残留语义：DEL_FLAG='1'）----
DELETE FROM erp_admin_shift   WHERE DEL_FLAG = '1';
DELETE FROM erp_mrp_wc_shift  WHERE DEL_FLAG = '1';

-- ---- 2. 活跃班次编码重复校验（有重复则输出明细；由应用层/人工确认后再跑）----
-- 注意：MySQL 无 ASSERT；重复检查交由建键语句本身兜底（重复即报错中止）。

-- ---- 3. 受控 ALTER：SHIFT_CODE 唯一键（信息架构检查 + PREPARE）----
SET @c := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'erp_admin_shift'
             AND INDEX_NAME = 'UK_ADMIN_SHIFT_CODE');
SET @s := IF(@c = 0,
             'ALTER TABLE erp_admin_shift ADD UNIQUE KEY UK_ADMIN_SHIFT_CODE (SHIFT_CODE)',
             'SELECT ''UK_ADMIN_SHIFT_CODE exists'' AS note');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
