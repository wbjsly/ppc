-- ============================================================
-- 127-mrp-cost-aggregation.sql
-- change: add-cost-aggregation（5.11 成本归集，spec production-cost-aggregation
--         + completion-cost-voucher + work-order-management MODIFIED C-4.5-09）
-- 顺序：1) 归集两表 + 费用池表  2) 生产成本科目 4 个  3) 参数 3 个
--       4) 生产成本会计角色  5) M5-11 四叶菜单与权限
-- 幂等：全部 IF NOT EXISTS / INSERT IGNORE / UPDATE（可连跑多遍）
-- ============================================================

-- ---------- 1) 归集汇总表（工单级，重建式一行） ----------
--     design D1：summary 单行承载料工费三金额、单位成本、四分解差异与差异报告状态；
--     重建式重跑 = 按 MO_ID 删行重插（实体继承 BaseEntity：DEL_FLAG/VER_NO/审计四列必齐）。
CREATE TABLE IF NOT EXISTS erp_mrp_cost_summary (
    ID VARCHAR(64) NOT NULL,
    MO_ID VARCHAR(64) NOT NULL COMMENT '工单 ID',
    MO_NO VARCHAR(64) NOT NULL DEFAULT '' COMMENT '工单号',
    PERIOD VARCHAR(6) NOT NULL DEFAULT '' COMMENT '归集期次 yyyyMM（归集执行时点）',
    MATERIAL_COST DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '直接材料合计',
    LABOR_COST DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '直接人工合计',
    OVERHEAD_COST DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '制造费用分摊合计',
    TOTAL_COST DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '总成本 = 料+工+费',
    GOOD_QTY DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '合格产出',
    UNIT_COST DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '单位成本 = 总成本/合格产出',
    STD_TOTAL_COST DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '标准总成本 = 标准成本×合格产出',
    VAR_TOTAL DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '总差异 = 实际 − 标准',
    VAR_PRICE DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '价格差异（生产侧恒 0，采购 1406 已确认）',
    VAR_VOLUME DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '用量差异（实算）',
    VAR_EFFICIENCY DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '效率差异（实算）',
    VAR_CAPACITY DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '产能差异（当期负荷近似）',
    VAR_RATE DECIMAL(9,4) NOT NULL DEFAULT 0 COMMENT '差异率 = |总差异|/标准总成本',
    REPORT_STATUS VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT '差异报告 NONE/RAISED/REVIEWED',
    MARK VARCHAR(255) DEFAULT NULL COMMENT '缺素提示（如 制造费用未归集）',
    CREATE_BY VARCHAR(64) DEFAULT NULL,
    CREATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY VARCHAR(64) DEFAULT NULL,
    UPDATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0',
    VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_MRP_COST_SUM_MO (MO_ID),
    KEY IDX_MRP_COST_SUM_PERIOD (PERIOD)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------- 1b) 归集明细行（料按物料 / 工按报工 / 费按期次池） ----------
CREATE TABLE IF NOT EXISTS erp_mrp_cost_line (
    ID VARCHAR(64) NOT NULL,
    MO_ID VARCHAR(64) NOT NULL COMMENT '工单 ID',
    COST_TYPE VARCHAR(16) NOT NULL COMMENT 'MATERIAL/LABOR/OH',
    DIM_KEY VARCHAR(64) NOT NULL COMMENT '维度键：物料编码/报工单号/池期次',
    DIM_NAME VARCHAR(128) DEFAULT NULL COMMENT '维度显示名',
    QTY DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '数量（投料量/工时/基准工时）',
    RATE DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '单价/费率/分摊率',
    AMOUNT DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '金额',
    MARK VARCHAR(64) DEFAULT NULL COMMENT '缺失标记 NO_PRICE/NO_POOL/NO_HOURS',
    CREATE_BY VARCHAR(64) DEFAULT NULL,
    CREATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY VARCHAR(64) DEFAULT NULL,
    UPDATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0',
    VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_MRP_COST_LINE_MO (MO_ID, COST_TYPE),
    KEY IDX_MRP_COST_LINE_DIM (DIM_KEY)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------- 1c) 制造费用池（design D2：期次唯一 + 池总额 + 分摊规则） ----------
CREATE TABLE IF NOT EXISTS erp_mrp_overhead_pool (
    ID VARCHAR(64) NOT NULL,
    PERIOD VARCHAR(6) NOT NULL COMMENT '期次 yyyyMM，唯一',
    POOL_AMT DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '当期制造费用总额',
    RULE VARCHAR(16) NOT NULL DEFAULT 'TIME' COMMENT '分摊规则 TIME/MACHINE/QTY（默认工时比例）',
    REMARK VARCHAR(255) DEFAULT NULL,
    CREATE_BY VARCHAR(64) DEFAULT NULL,
    CREATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY VARCHAR(64) DEFAULT NULL,
    UPDATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0',
    VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_MRP_OH_POOL_PERIOD (PERIOD)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------- 2) 生产成本科目 4 个（spec completion-cost-voucher 凭证五科目之四；借方复用既有 1405 库存商品） ----------
--     方向字段 = 科目正常余额方向；完工凭证对生产成本三明细记贷方（gl-voucher 不做行/科目方向一致性校验，仅校验 DR/CR 枚举）
INSERT IGNORE INTO erp_fin_account (ID, ACCOUNT_CODE, ACCOUNT_NAME, DIRECTION, CATEGORY, STATUS) VALUES
('ACC-500101', '500101', '生产成本-直接材料', 'DR', 'COST', 'ACTIVE'),
('ACC-500102', '500102', '生产成本-直接人工', 'DR', 'COST', 'ACTIVE'),
('ACC-500103', '500103', '生产成本-制造费用', 'DR', 'COST', 'ACTIVE'),
('ACC-500191', '500191', '生产成本差异',     'DR', 'COST', 'ACTIVE');

-- ---------- 3) 参数 3 个 ----------
INSERT IGNORE INTO erp_sys_param
(ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('param-cost-std-labor-rate', 'STD_LABOR_RATE', '50', 'NUMBER', 'MRP',
 '标准工资率（元/工时，FR-4.5-6-2 工时费率；spec 示例口径 50）', 'seed', '0', 0);
INSERT IGNORE INTO erp_sys_param
(ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('param-cost-variance-rate', 'COST_VARIANCE_RATE', '0.05', 'NUMBER', 'MRP',
 '成本差异率阈值（|总差异|/标准总成本 超此值生成差异报告并阻断关单，FR-4.5-6-5）', 'seed', '0', 0);
INSERT IGNORE INTO erp_sys_param
(ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('param-cost-closed-periods', 'COST_CLOSED_PERIODS', '', 'STRING', 'MRP',
 '已关闭会计期间（逗号分隔 yyyyMM，如 202609；空=无关闭期间）。凭证期次命中 → 落 DRAFT 待下期过账（FR-4.5-6-7）', 'seed', '0', 0);

-- ---------- 4) 生产成本会计角色（spec 00-erp-spec 角色表 2352 行；按 126 role-purchaser 先例补种子） ----------
INSERT IGNORE INTO erp_admin_role (ID, ROLE_CODE, ROLE_NAME, SORT_ORDER, STATUS, CREATE_BY, CREATE_DATE) VALUES
('role-cost-accountant', 'ROLE_COST_ACCOUNTANT', '生产成本会计', 50, '1', 'system', NOW());

-- ---------- 5) M5-11 成本归集四叶菜单与权限 ----------
--     新装库：重跑 generate-menu-seed.cjs 后由 099 落地（EXTRA_PERM 本变更已增补）；存量库靠本节收敛。
--     权限两条半线：5.11.1-3 = 成本会计 + 计划线只读；5.11.4 = 成本会计 + 财务只读；父取子超集防孤儿。
INSERT IGNORE INTO erp_admin_menu (ID, PARENT_ID, MENU_CODE, TITLE, PATH, ICON, SORT_ORDER, PERM, STATUS) VALUES
('M5-11', 'M5', '5.11', '成本归集', '', '', '110', '', '1'),
('M5-11-1', 'M5-11', '5.11.1', '直接材料', '/m/5.11.1', '', '10', '', '1'),
('M5-11-2', 'M5-11', '5.11.2', '直接人工', '/m/5.11.2', '', '20', '', '1'),
('M5-11-3', 'M5-11', '5.11.3', '制造费用', '/m/5.11.3', '', '30', '', '1'),
('M5-11-4', 'M5-11', '5.11.4', '完工凭证', '/m/5.11.4', '', '40', '', '1');

--     存量库 PERM 收敛（与 099 生成结果同口径；父=子超集）
UPDATE erp_admin_menu
   SET PERM = 'ROLE_COST_ACCOUNTANT,ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_FINANCE,ROLE_FINANCE_MGR,ROLE_ADMIN'
 WHERE ID = 'M5-11';
UPDATE erp_admin_menu
   SET PERM = 'ROLE_COST_ACCOUNTANT,ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_ADMIN'
 WHERE ID IN ('M5-11-1', 'M5-11-2', 'M5-11-3');
UPDATE erp_admin_menu
   SET PERM = 'ROLE_COST_ACCOUNTANT,ROLE_FINANCE,ROLE_FINANCE_MGR,ROLE_ADMIN'
 WHERE ID = 'M5-11-4';
