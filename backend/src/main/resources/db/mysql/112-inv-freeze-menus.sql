-- 112 冻结管理四菜单：M4-9-4 影响通知 + 4.9.x PERM 存量回填 + 冻结影响快照列
--     + 拣货任务/波次暂停标记列 + 参数 FREEZE_IMPACT_RATIO
--     （change add-freeze-management-menus，spec freeze-management ADDED 四需求；design D1/D2/D5/D6）
-- 幂等：INSERT IGNORE / UPDATE 幂等 / information_schema+PREPARE 动态 DDL

-- ---------- 1) 菜单行：4.9.4 影响通知（099 已重生成含本行，存量库由本迁移补） ----------
INSERT IGNORE INTO erp_admin_menu
(ID, PARENT_ID, MENU_CODE, TITLE, PATH, ICON, SORT_ORDER, PERM, STATUS) VALUES
('M4-9-4', 'M4-9', '4.9.4', '影响通知', '/m/4.9.4', '', '40', 'ROLE_WAREHOUSE,ROLE_ADMIN', '1');

-- ---------- 2) 菜单 PERM 存量回填（099 INSERT IGNORE 对已执行库不更新 → UPDATE；沿 109 范式） ----------
UPDATE erp_admin_menu SET PERM = 'ROLE_QUALITY_ENG,ROLE_QUALITY_MGR,ROLE_ADMIN'
 WHERE ID = 'M4-9-1';
UPDATE erp_admin_menu SET PERM = 'ROLE_FINANCE,ROLE_FINANCE_MGR,ROLE_ADMIN'
 WHERE ID = 'M4-9-2';
UPDATE erp_admin_menu SET PERM = 'ROLE_QUALITY_MGR,ROLE_FINANCE_MGR,ROLE_ADMIN'
 WHERE ID = 'M4-9-3';
UPDATE erp_admin_menu SET PERM = 'ROLE_WAREHOUSE,ROLE_ADMIN'
 WHERE ID = 'M4-9-4';

-- ---------- 3) 冻结影响快照（design D2：SO/PO/工单三级清单 JSON，执行时写入，解冻保留作审计） ----------
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'erp_inv_freeze'
                 AND COLUMN_NAME = 'IMPACT_JSON') = 0,
  'ALTER TABLE erp_inv_freeze
     ADD COLUMN IMPACT_JSON MEDIUMTEXT NULL
       COMMENT ''影响面快照：{sos:[{soNo,soLineId,qty,precision}],pos:[{poNo,lineNo,qty,precision}],mos:[],moDataSource:false}（FR-4.4-5-4/5.5，4.9.4 展开）''
       AFTER REMARK', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 4) 拣货任务暂停标记（design D1：冻结执行挂 CREATED/PICKING，恢复回原状态） ----------
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'erp_pick_task'
                 AND COLUMN_NAME = 'PAUSE_FREEZE_NO') = 0,
  'ALTER TABLE erp_pick_task
     ADD COLUMN PAUSE_FREEZE_NO VARCHAR(64) NULL
       COMMENT ''暂停来源冻结单号（解冻 RELEASED 后方可恢复）''
       AFTER STATUS,
     ADD COLUMN PAUSE_FROM_STATUS VARCHAR(20) NULL
       COMMENT ''暂停前状态（恢复时 CAS 回该状态）''
       AFTER PAUSE_FREEZE_NO', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 5) 波次暂停标记（同上；挂 CREATED/ALLOCATED/PICKING/SORTING，STAGING/SHIPPING 不挂） ----------
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'erp_inv_wave'
                 AND COLUMN_NAME = 'PAUSE_FREEZE_NO') = 0,
  'ALTER TABLE erp_inv_wave
     ADD COLUMN PAUSE_FREEZE_NO VARCHAR(64) NULL
       COMMENT ''暂停来源冻结单号（解冻 RELEASED 后方可恢复）''
       AFTER STATUS,
     ADD COLUMN PAUSE_FROM_STATUS VARCHAR(20) NULL
       COMMENT ''暂停前状态（恢复时 CAS 回该状态）''
       AFTER PAUSE_FREEZE_NO', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 6) 参数种子：影响评估确认阈值（FR-4.4-5-3 异常列，design D5） ----------
INSERT IGNORE INTO erp_sys_param
(ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('param-freeze-impact-ratio', 'FREEZE_IMPACT_RATIO', '50', 'PERCENT', 'INV',
 '冻结影响评估阈值：scope=ALL 或 冻结量 ≥ 该值% × 维度可用量 时发起端二次确认（FR-4.4-5-3）', 'seed', '0', 0);
