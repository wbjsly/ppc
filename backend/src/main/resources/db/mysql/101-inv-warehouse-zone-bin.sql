-- 101 仓库管理组主数据（change add-warehouse-zone-management，spec warehouse-zone-planning / warehouse-attribute-config / warehouse-master）
-- 4.1.1 仓库类型列 + 4.1.2 区域/仓位三表 + 4.1.3 五类字典种子
-- 幂等：CREATE IF NOT EXISTS / INSERT IGNORE / information_schema + PREPARE 动态 DDL（040、055 范式）

-- ---------- 1) 仓库档案加仓库类型列（spec warehouse-master，design D8） ----------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_inv_warehouse'
        AND COLUMN_NAME = 'WH_TYPE') = 0,
    'ALTER TABLE erp_inv_warehouse
       ADD COLUMN WH_TYPE VARCHAR(32) DEFAULT NULL
         COMMENT ''仓库类型（WAREHOUSE_TYPE 字典 code；NULL=存量未分类）'' AFTER ORG_UNIT',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 存量回填普通仓（仅填空值，不覆盖已有值，可重复执行）
UPDATE erp_inv_warehouse SET WH_TYPE = 'NORMAL'
 WHERE WH_TYPE IS NULL OR WH_TYPE = '';

-- ---------- 2) 区域（zone，spec warehouse-zone-planning，design D1） ----------
CREATE TABLE IF NOT EXISTS erp_inv_zone (
    ID VARCHAR(64) NOT NULL,
    WH_CODE VARCHAR(32) NOT NULL COMMENT '仓库编码（引用 erp_inv_warehouse.WH_CODE）',
    ZONE_CODE VARCHAR(32) NOT NULL COMMENT '区域编码（仓库内唯一，创建后不可改；仓位编号前缀）',
    ZONE_NAME VARCHAR(128) NOT NULL COMMENT '区域名称',
    SORT_ORDER INT NOT NULL DEFAULT 0,
    -- 1 启用 / 0 停用（停用后禁建仓位，既有仓位不级联）
    STATUS VARCHAR(1) NOT NULL DEFAULT '1' COMMENT '1 启用 / 0 停用',
    -- B2 区域默认属性（design D3：写入时物化进仓位行）
    DEF_BIN_TYPE VARCHAR(32) DEFAULT NULL COMMENT '默认仓位类型（BIN_TYPE 字典 code）',
    DEF_TEMP_LEVEL VARCHAR(32) DEFAULT NULL COMMENT '默认温湿度等级（TEMP_LEVEL 字典 code）',
    DEF_HAZARD_LEVEL VARCHAR(32) DEFAULT NULL COMMENT '默认危化品等级（HAZARD_LEVEL 字典 code）',
    DEF_CLEAN_LEVEL VARCHAR(32) DEFAULT NULL COMMENT '默认洁净等级（CLEAN_LEVEL 字典 code）',
    DEF_CAPACITY_PALLET INT DEFAULT NULL COMMENT '默认托位容量（NULL=不限）',
    REMARK VARCHAR(500) DEFAULT NULL,
    CREATE_BY VARCHAR(64) DEFAULT NULL,
    CREATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY VARCHAR(64) DEFAULT NULL,
    UPDATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0',
    VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_INV_ZONE_CODE (WH_CODE, ZONE_CODE),
    KEY IDX_INV_ZONE_WH (WH_CODE, STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='仓库区域（4.1.2）';

-- ---------- 3) 区域版本快照（design D1，仿 erp_mdm_org_unit_version；仓位不建版本表，偏差表 #4） ----------
CREATE TABLE IF NOT EXISTS erp_inv_zone_version (
    ID VARCHAR(64) NOT NULL,
    ENTITY_ID VARCHAR(64) NOT NULL,
    VERSION_NO INT NOT NULL,
    SNAPSHOT_JSON LONGTEXT NOT NULL,
    DIFF_SUMMARY VARCHAR(500),
    OP_TYPE VARCHAR(16) NOT NULL,
    CREATE_BY VARCHAR(64),
    CREATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_INV_ZV (ENTITY_ID, VERSION_NO)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='仓库区域版本快照（4.1.2）';

-- ---------- 4) 仓位（bin，spec warehouse-zone-planning，design D1/D2） ----------
CREATE TABLE IF NOT EXISTS erp_inv_bin (
    ID VARCHAR(64) NOT NULL,
    WH_CODE VARCHAR(32) NOT NULL COMMENT '仓库编码',
    ZONE_CODE VARCHAR(32) NOT NULL COMMENT '区域编码（与 WH_CODE 联合定位区域）',
    BIN_CODE VARCHAR(64) NOT NULL COMMENT '仓位编号 = 区域码-排2位-列2位-层2位（系统生成，创建后不可改）',
    BIN_SEQ INT NOT NULL COMMENT '排',
    COL_NO INT NOT NULL COMMENT '列',
    LAYER_NO INT NOT NULL COMMENT '层',
    -- 继承区域 DEF_* 后可单独覆盖（design D3 写入时物化）
    BIN_TYPE VARCHAR(32) DEFAULT NULL COMMENT '仓位类型（BIN_TYPE 字典 code）',
    TEMP_LEVEL VARCHAR(32) DEFAULT NULL COMMENT '温湿度等级（TEMP_LEVEL 字典 code）',
    HAZARD_LEVEL VARCHAR(32) DEFAULT NULL COMMENT '危化品等级（HAZARD_LEVEL 字典 code）',
    CLEAN_LEVEL VARCHAR(32) DEFAULT NULL COMMENT '洁净等级（CLEAN_LEVEL 字典 code）',
    CAPACITY_PALLET INT DEFAULT NULL COMMENT '托位容量（NULL=不限，偏差表 #1 不做体积匹配）',
    STATUS VARCHAR(1) NOT NULL DEFAULT '1' COMMENT '1 启用 / 0 停用',
    REMARK VARCHAR(500) DEFAULT NULL,
    CREATE_BY VARCHAR(64) DEFAULT NULL,
    CREATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY VARCHAR(64) DEFAULT NULL,
    UPDATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0',
    VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_INV_BIN_CODE (BIN_CODE),
    KEY IDX_INV_BIN_ZONE (WH_CODE, ZONE_CODE, STATUS),
    KEY IDX_INV_BIN_STATUS (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='仓库仓位（4.1.2）';

-- ---------- 5) 五类字典种子（spec warehouse-attribute-config；HAZARD_LEVEL/CLEAN_LEVEL 规格无取值域，留空由页面维护） ----------
INSERT IGNORE INTO erp_mdm_item_dict (ID, DICT_TYPE, DICT_CODE, DICT_NAME, SORT_ORDER, STATUS, CREATE_BY) VALUES
    -- WAREHOUSE_TYPE 仓库类型（proposal 4.1.1 补差距）
    ('d-wh-normal', 'WAREHOUSE_TYPE', 'NORMAL',   '普通仓',   10, '1', 'system'),
    ('d-wh-chill',  'WAREHOUSE_TYPE', 'CHILLED',  '冷藏仓',   20, '1', 'system'),
    ('d-wh-haz',    'WAREHOUSE_TYPE', 'HAZARD',   '危化品仓', 30, '1', 'system'),
    ('d-wh-ret',    'WAREHOUSE_TYPE', 'RETURN',   '退货仓',   40, '1', 'system'),
    -- BIN_TYPE 仓位类型（规格 1873 行枚举：存储/拣货/发货/退货/残次品区）
    ('d-bin-store', 'BIN_TYPE', 'STORE',   '存储区',   10, '1', 'system'),
    ('d-bin-pick',  'BIN_TYPE', 'PICK',    '拣货区',   20, '1', 'system'),
    ('d-bin-ship',  'BIN_TYPE', 'SHIP',    '发货区',   30, '1', 'system'),
    ('d-bin-ret',   'BIN_TYPE', 'RETURN',  '退货区',   40, '1', 'system'),
    ('d-bin-scr',   'BIN_TYPE', 'SCRAP',   '残次品区', 50, '1', 'system'),
    -- TEMP_LEVEL 温湿度等级（需求讨论定档：常温/阴凉/冷藏/冷冻）
    ('d-temp-amb',  'TEMP_LEVEL', 'AMBIENT', '常温', 10, '1', 'system'),
    ('d-temp-cool', 'TEMP_LEVEL', 'COOL',    '阴凉', 20, '1', 'system'),
    ('d-temp-chl',  'TEMP_LEVEL', 'CHILLED', '冷藏', 30, '1', 'system'),
    ('d-temp-froz', 'TEMP_LEVEL', 'FROZEN',  '冷冻', 40, '1', 'system');

-- ---------- 6) 存量库菜单 PERM 补丁（任务 6.2） ----------
-- 099 已被 marker 跳过不会重跑：M4-1-2/M4-1-3 的 PERM 在 099 产物中已更新，
-- 但存量库需此处幂等补齐（UPDATE ... WHERE 对已正确行零影响）。
-- 重建库走 099，本段 UPDATE 结果与之同口径。
UPDATE erp_admin_menu
   SET PERM = 'ROLE_WAREHOUSE,ROLE_ADMIN'
 WHERE ID IN ('M4-1-1', 'M4-1-2', 'M4-1-3');
