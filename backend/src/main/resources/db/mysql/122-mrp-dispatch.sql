-- 122 生产管理-派工管理（5.6，spec 4.5-04 派工）：五新表 + 车间主管角色 + 四参数 + 白班种子 + 菜单回填
--     （change add-dispatch-management，spec dispatch-management ADDED 十一需求 /
--       capacity-load ADDED 六需求 / outsourcing-dispatch ADDED 六需求 /
--       material-issue MODIFIED 领料过账；design D1：单迁移只增不改——
--       日历/班次挂 erp_admin_（系统管理域主数据），WC 绑班次用独立表避免 ALTER 归档的 118 工作中心表）
-- 幂等：CREATE TABLE IF NOT EXISTS / INSERT IGNORE / UPDATE（可重复执行）

-- ---------- 1) 派工单（design D1：工序级一单一序，状态 PENDING_CONFIRM→RELEASED/CANCELLED） ----------
CREATE TABLE IF NOT EXISTS erp_mrp_dispatch (
    ID VARCHAR(64) NOT NULL,
    DISPATCH_NO VARCHAR(64) NOT NULL COMMENT '派工单号 DP+yyyyMMdd+流水',
    MO_ID VARCHAR(64) NOT NULL COMMENT '所属工单',
    MO_NO VARCHAR(64) NOT NULL COMMENT '工单号（冗余查询）',
    OP_SEQ INT NOT NULL COMMENT '工序序号（工序级派工，BR-4.5-19 口径 D7：工单级=批量生成）',
    OP_NAME VARCHAR(128) DEFAULT NULL COMMENT '工序名称（冗余展示）',
    WC_CODE VARCHAR(64) NOT NULL COMMENT '工作中心',
    WC_NAME VARCHAR(128) DEFAULT NULL,
    WC_TYPE VARCHAR(16) NOT NULL DEFAULT 'INTERNAL' COMMENT 'INTERNAL/OUTSOURCED（外协标记同步 OUTSOURCE_FLAG，枚举值随 118 迁移口径）',
    PLAN_START DATE DEFAULT NULL COMMENT '排程计划开始（日期粒度，偏差 D5 仅前向）',
    PLAN_END DATE DEFAULT NULL COMMENT '排程计划结束（超 planEndDate 置 RISK_FLAG）',
    DURATION_HOURS DECIMAL(8,2) NOT NULL DEFAULT 0 COMMENT '占用工时 = setup + run（时段装箱输入）',
    STATUS VARCHAR(20) NOT NULL DEFAULT 'PENDING_CONFIRM' COMMENT 'PENDING_CONFIRM/RELEASED/CANCELLED（预留 5.7 扩展）',
    RISK_FLAG VARCHAR(1) NOT NULL DEFAULT '0' COMMENT '交期风险（FR-4.5-4-3，黄标）',
    SHORTAGE_FIRST_FLAG VARCHAR(1) NOT NULL DEFAULT '0' COMMENT '缺料先行（C-4.5-12 审批通过置位）',
    OUTSOURCE_FLAG VARCHAR(1) NOT NULL DEFAULT '0' COMMENT '外协派工（BR-4.5-21）',
    CONFIRM_BY VARCHAR(64) DEFAULT NULL COMMENT '确认人（车间主管/计划主管/管理员）',
    CONFIRM_AT DATETIME DEFAULT NULL,
    CANCEL_REASON VARCHAR(500) DEFAULT NULL,
    PR_NO VARCHAR(64) DEFAULT NULL COMMENT '外协回联：请购单号（PENDING_PR→ORDERED）',
    PO_NO VARCHAR(64) DEFAULT NULL COMMENT '外协回联：采购订单号（ORDERED）',
    RECEIPT_FLAG VARCHAR(1) NOT NULL DEFAULT '0' COMMENT '外协回联：GR 收货完成 RECEIVED 置 1',
    REMARK VARCHAR(500) DEFAULT NULL COMMENT '重排留痕等',
    CREATE_BY VARCHAR(64) DEFAULT NULL,
    CREATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY VARCHAR(64) DEFAULT NULL,
    UPDATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0',
    VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_MRP_DISP_NO (DISPATCH_NO),
    KEY IDX_MRP_DISP_MO (MO_ID, OP_SEQ),
    KEY IDX_MRP_DISP_STATUS (STATUS),
    KEY IDX_MRP_DISP_WC_DATE (WC_CODE, PLAN_START)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------- 2) 物料预扣（design D2：实扣 AVAILABLE_QTY + 核销余量护栏；按物料聚合） ----------
--     ACTIVE_FLAG 技巧：活动行 = '1'、关闭行 = NULL —— UNIQUE(MO_ID, ITEM_CODE, ACTIVE_FLAG)
--     保证同工单同物料至多一条活动预扣（幂等锚点），关闭行可无限累积（多次取消/重派历史）
CREATE TABLE IF NOT EXISTS erp_mrp_reservation (
    ID VARCHAR(64) NOT NULL,
    MO_ID VARCHAR(64) NOT NULL COMMENT '所属工单',
    MO_NO VARCHAR(64) NOT NULL COMMENT '工单号（领料单 WORK_ORDER_NO 核销匹配）',
    ITEM_CODE VARCHAR(64) NOT NULL COMMENT '物料（同工单同物料 BOM 行聚合）',
    ITEM_NAME VARCHAR(128) DEFAULT NULL,
    REQ_QTY DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '需求数量（BOM 快照合计）',
    RESERVED_QTY DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '实扣数量 = min(可用, 需求)；OFF 开关时 0',
    REMAIN_QTY DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '核销余量（领料过账 min(行量, 余量) 递减）',
    STATUS VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/CLEARED/RELEASED',
    ACTIVE_FLAG VARCHAR(1) DEFAULT NULL COMMENT '活动=1 关闭=NULL（唯一索引多 NULL 技巧）',
    RELEASE_REASON VARCHAR(200) DEFAULT NULL COMMENT '取消/关闭/驳回释放原因',
    TAKES_JSON VARCHAR(2000) DEFAULT NULL COMMENT '逐库存行实扣明细 [{"id":"stockId","qty":"10"}]，回补按 take 归位',
    CREATE_BY VARCHAR(64) DEFAULT NULL,
    CREATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY VARCHAR(64) DEFAULT NULL,
    UPDATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0',
    VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_MRP_RESV_ACTIVE (MO_ID, ITEM_CODE, ACTIVE_FLAG),
    KEY IDX_MRP_RESV_MO (MO_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------- 3) 工作日历（capacity-load：全局主数据，16.11 维护；日期种子见本文件第 6 节） ----------
CREATE TABLE IF NOT EXISTS erp_admin_work_calendar (
    ID VARCHAR(64) NOT NULL,
    CAL_DATE DATE NOT NULL COMMENT '日历日期',
    IS_WORKDAY VARCHAR(1) NOT NULL DEFAULT '1' COMMENT '1 工作日 / 0 非工作日',
    REMARK VARCHAR(200) DEFAULT NULL COMMENT '节假日/调休备注',
    CREATE_BY VARCHAR(64) DEFAULT NULL,
    CREATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY VARCHAR(64) DEFAULT NULL,
    UPDATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0',
    VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_ADMIN_CAL_DATE (CAL_DATE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------- 4) 班次模板（capacity-load：16.11.2 维护；种子见本文件第 5 节） ----------
CREATE TABLE IF NOT EXISTS erp_admin_shift (
    ID VARCHAR(64) NOT NULL,
    SHIFT_CODE VARCHAR(32) NOT NULL COMMENT '班次编码（创建后不可改）',
    SHIFT_NAME VARCHAR(64) NOT NULL,
    START_TIME VARCHAR(8) NOT NULL COMMENT 'HH:mm',
    END_TIME VARCHAR(8) NOT NULL COMMENT 'HH:mm',
    HOURS DECIMAL(6,2) NOT NULL COMMENT '班次工时（>0，扣午休后净值）',
    STATUS VARCHAR(1) NOT NULL DEFAULT '1',
    CREATE_BY VARCHAR(64) DEFAULT NULL,
    CREATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY VARCHAR(64) DEFAULT NULL,
    UPDATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0',
    VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID)
    -- 不设 SHIFT_CODE 物理唯一键：逻辑删除（@TableLogic，主数据禁止硬删基线）的同码墓碑
    -- 会撞物理唯一 →「删除后重建同码班次」必败；编码唯一由 Service 层 del 感知 selectCount 保
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------- 5) 工作中心班次绑定（design D3：首期单班次绑定，结构支持多班次；不 ALTER 归档的 118 工作中心表） ----------
CREATE TABLE IF NOT EXISTS erp_mrp_wc_shift (
    ID VARCHAR(64) NOT NULL,
    WC_CODE VARCHAR(64) NOT NULL COMMENT '工作中心',
    SHIFT_CODE VARCHAR(32) NOT NULL COMMENT '班次模板编码（删除被引用班次须先解绑，spec 被引用禁删）',
    CREATE_BY VARCHAR(64) DEFAULT NULL,
    CREATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY VARCHAR(64) DEFAULT NULL,
    UPDATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0',
    VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID)
    -- 不设 (WC_CODE, SHIFT_CODE) 物理唯一键：解绑（逻辑删）后重绑同一组合会撞墓碑；
    -- 绑定唯一性由 Service 层「该 WC 活动绑定替换式 upsert」保（首期单班次）
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------- 5.1 存量库去物理唯一（逻辑删除与物理唯一不兼容：同码重建/解绑重绑必撞；
--     已建库幂等执行，未建库走上面无唯一版 CREATE） ----------
SET @sql := (SELECT IF(COUNT(*) > 0,
    'ALTER TABLE erp_admin_shift DROP INDEX UK_ADMIN_SHIFT_CODE', 'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_admin_shift'
      AND INDEX_NAME = 'UK_ADMIN_SHIFT_CODE');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql := (SELECT IF(COUNT(*) > 0,
    'ALTER TABLE erp_mrp_wc_shift DROP INDEX UK_MRP_WC_SHIFT', 'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_mrp_wc_shift'
      AND INDEX_NAME = 'UK_MRP_WC_SHIFT');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 6) 白班班次种子（capacity-load：默认白班，WC 未绑定时按此展开时段） ----------
INSERT IGNORE INTO erp_admin_shift
(ID, SHIFT_CODE, SHIFT_NAME, START_TIME, END_TIME, HOURS, STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('shift-day', 'DAY', '白班', '08:00', '17:00', 8.00, '1', 'seed', '0', 0);

-- ---------- 7) 车间主管角色（dispatch-management：FR-4.5-4-6 派工确认人；5.5 偏差回补） ----------
INSERT IGNORE INTO erp_admin_role (ID, ROLE_CODE, ROLE_NAME, SORT_ORDER, STATUS, CREATE_BY, CREATE_DATE) VALUES
('role-shop-floor', 'ROLE_SHOP_FLOOR', '车间主管', 45, '1', 'system', NOW());

-- ---------- 8) 四参数（design D1） ----------
INSERT IGNORE INTO erp_sys_param
(ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('param-capacity-load-trigger', 'CAPACITY_LOAD_TRIGGER', '110', 'PERCENT', 'MRP',
 '产能负荷预警阈值（C-4.5-02 L4 提示）：负荷率超此百分比标红并提示外协建议入口，仅提示不阻断派工', 'seed', '0', 0);
INSERT IGNORE INTO erp_sys_param
(ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('param-kitting-calendar-fallback', 'KITTING_CALENDAR_FALLBACK', 'WEEKDAY', 'ENUM', 'MRP',
 '日历缺失降级：WEEKDAY=无日历行按周一~五可用/周末不可用；DISABLED=无日历行显式报错（禁止静默全可用/全不可用，design D1+偏差 D13）', 'seed', '0', 0);
INSERT IGNORE INTO erp_sys_param
(ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('param-dispatch-reservation', 'DISPATCH_RESERVATION', 'ON', 'ENUM', 'MRP',
 '派工预扣开关：ON=派工确认实扣 AVAILABLE_QTY（design D2）；OFF=仅记账不实扣（降级为独立记账行为，账漂一键退路）', 'seed', '0', 0);
INSERT IGNORE INTO erp_sys_param
(ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('param-dispatch-due-sweep-ms', 'DISPATCH_DUE_SWEEP_MS', '300000', 'COUNT', 'MRP',
 '到期未派扫描间隔（毫秒，默认 5 分钟）：扫计划开工日≤今日且有未派工序的 Released 工单推 MO_DISPATCH_DUE，0=停用', 'seed', '0', 0);

-- ---------- 9) 菜单 PERM 存量回填（新装库由 099 生成；已执行过 099 的库靠本节收敛） ----------
--     M5-6 父与 5.6.1/5.6.2 四角色超集 ⊇ 5.6.3 三角色（防孤儿，父 ⊇ 子）；
--     M16-11 工作日历 ADMIN 维护 + PLANNER 只读 → 父 M16 取并集（M3 域先例）
UPDATE erp_admin_menu
   SET PERM = 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_SHOP_FLOOR,ROLE_ADMIN'
 WHERE ID IN ('M5-6', 'M5-6-1', 'M5-6-2');
UPDATE erp_admin_menu
   SET PERM = 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_ADMIN'
 WHERE ID = 'M5-6-3';
UPDATE erp_admin_menu
   SET PERM = 'ROLE_ADMIN,ROLE_PLANNER'
 WHERE ID = 'M16';
INSERT IGNORE INTO erp_admin_menu (ID, PARENT_ID, MENU_CODE, TITLE, PATH, ICON, SORT_ORDER, PERM, STATUS) VALUES
('M16-11', 'M16', '16.11', '工作日历', '', '', '110', 'ROLE_ADMIN,ROLE_PLANNER', '1'),
('M16-11-1', 'M16-11', '16.11.1', '工作日历', '/m/16.11.1', '', '10', 'ROLE_ADMIN,ROLE_PLANNER', '1'),
('M16-11-2', 'M16-11', '16.11.2', '班次模板', '/m/16.11.2', '', '20', 'ROLE_ADMIN,ROLE_PLANNER', '1');

-- ---------- 10) 工作日历种子 2026—2027（capacity-load；design D1 + 任务 1.3） ----------
--     2026 按《国务院办公厅关于2026年部分节假日安排的通知》国办发明电〔2025〕7号（2025-11-04 发布）；
--     2027 安排尚未发布（往年 11 月中旬）→ 仅按周末规则种子，发布后人工补标（proposal 偏差 D13 运维事项，
--     FALLBACK 只救缺行不救错行，补标为唯一正解）。幂等：INSERT IGNORE + 可重复 UPDATE。

-- 10.1 全日期行（递归 CTE 730 天；WEEKDAY() 周六日标非工作日，调休上班日在 10.2 反转）
INSERT IGNORE INTO erp_admin_work_calendar (ID, CAL_DATE, IS_WORKDAY, REMARK, CREATE_BY, DEL_FLAG, VER_NO)
WITH RECURSIVE cal_days AS (
    SELECT DATE('2026-01-01') AS dt
    UNION ALL
    SELECT dt + INTERVAL 1 DAY FROM cal_days WHERE dt < DATE('2027-12-31')
)
SELECT CONCAT('cal-', DATE_FORMAT(dt, '%Y%m%d')), dt,
       IF(WEEKDAY(dt) >= 5, '0', '1'), NULL, 'seed', '0', 0
FROM cal_days;

-- 10.2 2026 法定节假日（放假区间标非工作日）
UPDATE erp_admin_work_calendar SET IS_WORKDAY='0', REMARK='元旦'      WHERE CAL_DATE BETWEEN '2026-01-01' AND '2026-01-03';
UPDATE erp_admin_work_calendar SET IS_WORKDAY='0', REMARK='春节'      WHERE CAL_DATE BETWEEN '2026-02-15' AND '2026-02-23';
UPDATE erp_admin_work_calendar SET IS_WORKDAY='0', REMARK='清明节'    WHERE CAL_DATE BETWEEN '2026-04-04' AND '2026-04-06';
UPDATE erp_admin_work_calendar SET IS_WORKDAY='0', REMARK='劳动节'    WHERE CAL_DATE BETWEEN '2026-05-01' AND '2026-05-05';
UPDATE erp_admin_work_calendar SET IS_WORKDAY='0', REMARK='端午节'    WHERE CAL_DATE BETWEEN '2026-06-19' AND '2026-06-21';
UPDATE erp_admin_work_calendar SET IS_WORKDAY='0', REMARK='中秋节'    WHERE CAL_DATE BETWEEN '2026-09-25' AND '2026-09-27';
UPDATE erp_admin_work_calendar SET IS_WORKDAY='0', REMARK='国庆节'    WHERE CAL_DATE BETWEEN '2026-10-01' AND '2026-10-07';

-- 10.3 2026 调休上班日（周末但上班，反转为工作日）
UPDATE erp_admin_work_calendar SET IS_WORKDAY='1', REMARK='调休上班'
 WHERE CAL_DATE IN ('2026-01-04', '2026-02-14', '2026-02-28', '2026-05-09', '2026-09-20', '2026-10-10');


-- 11. 外协 PR 来源扩宽（design D5：sourceType=OUTSOURCE 共 9 字符 > 原 VARCHAR(8)；
--     MODIFY COLUMN 幂等可重复执行，既有 MRP/MANUAL 值与 IDX_PR_SOURCE 索引不受影响）
ALTER TABLE erp_proc_requisition MODIFY COLUMN SOURCE_TYPE VARCHAR(16) NOT NULL
    COMMENT '来源类型：MRP / MANUAL / OUTSOURCE（外协漏斗，122 扩宽）';
