-- 015 MDM 物料分类与字典（物料管理 1.2.x 依赖，域前缀 erp_mdm，规范见 docs/design/01-architecture-style.md 1.4）

CREATE TABLE IF NOT EXISTS erp_mdm_item_category (
    ID VARCHAR(64) NOT NULL,
    CATEGORY_CODE VARCHAR(4) NOT NULL,
    CATEGORY_NAME VARCHAR(64) NOT NULL,
    ITEM_PREFIX VARCHAR(8) NOT NULL,
    PARENT_ID VARCHAR(64) NOT NULL DEFAULT '',
    LEVEL INT NOT NULL DEFAULT 1,
    STATUS VARCHAR(1) NOT NULL DEFAULT '1',
    CREATE_BY VARCHAR(64),
    CREATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY VARCHAR(64),
    UPDATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0',
    VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_MDM_IC_CODE (CATEGORY_CODE),
    KEY IDX_MDM_IC_PARENT (PARENT_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS erp_mdm_item_dict (
    ID VARCHAR(64) NOT NULL,
    DICT_TYPE VARCHAR(32) NOT NULL,
    DICT_CODE VARCHAR(32) NOT NULL,
    DICT_NAME VARCHAR(64) NOT NULL,
    SORT_ORDER INT NOT NULL DEFAULT 0,
    STATUS VARCHAR(1) NOT NULL DEFAULT '1',
    CREATE_BY VARCHAR(64),
    CREATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_MDM_ID (DICT_TYPE, DICT_CODE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 三大类种子（编码规则权威来源：ITEM_PREFIX + 4位分类码 + 6位流水）
INSERT IGNORE INTO erp_mdm_item_category (ID, CATEGORY_CODE, CATEGORY_NAME, ITEM_PREFIX, PARENT_ID, LEVEL, STATUS, CREATE_BY) VALUES
    ('ic-fg', '0002', '成品',   'FG', '', 1, '1', 'system'),
    ('ic-rm', '0001', '原材料', 'RM', '', 1, '1', 'system'),
    ('ic-wip', '0003', '半成品', 'WIP', '', 1, '1', 'system'),
    -- 示例二级分类（挂在三大类下，编码沿用父级前缀与分类码，1.2.3 建管理界面后可扩展）
    ('ic-fg-e', '0005', '成品-电子',   'FG', 'ic-fg', 2, '1', 'system'),
    ('ic-rm-s', '0004', '原材料-结构件', 'RM', 'ic-rm', 2, '1', 'system');

-- 字典种子：单位 / 物料组 / 存储条件（采购类型为后端枚举，见 design Decision 3）
INSERT IGNORE INTO erp_mdm_item_dict (ID, DICT_TYPE, DICT_CODE, DICT_NAME, SORT_ORDER, STATUS, CREATE_BY) VALUES
    ('d-u-pc',  'UNIT', 'PC',  '个',   10, '1', 'system'),
    ('d-u-kg',  'UNIT', 'KG',  '千克', 20, '1', 'system'),
    ('d-u-m',   'UNIT', 'M',   '米',   30, '1', 'system'),
    ('d-u-l',   'UNIT', 'L',   '升',   40, '1', 'system'),
    ('d-u-box', 'UNIT', 'BOX', '箱',   50, '1', 'system'),
    ('d-u-ea',  'UNIT', 'EA',  '件',   60, '1', 'system'),
    ('d-g-str', 'MATERIAL_GROUP', 'STRUCT', '结构件', 10, '1', 'system'),
    ('d-g-ele', 'MATERIAL_GROUP', 'ELEC',   '电子件', 20, '1', 'system'),
    ('d-g-pkg', 'MATERIAL_GROUP', 'PKG',    '包装物', 30, '1', 'system'),
    ('d-g-gen', 'MATERIAL_GROUP', 'GEN',    '通用耗材', 40, '1', 'system'),
    ('d-s-nor', 'STORAGE', 'NORMAL', '常温',   10, '1', 'system'),
    ('d-s-chi', 'STORAGE', 'CHILL',  '冷藏',   20, '1', 'system'),
    ('d-s-fro', 'STORAGE', 'FROZEN', '冷冻',   30, '1', 'system'),
    ('d-s-haz', 'STORAGE', 'HAZARD', '危险品', 40, '1', 'system');
