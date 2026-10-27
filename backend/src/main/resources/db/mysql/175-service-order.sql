-- ============================================================
-- 175-service-order.sql
-- 客户投诉与服务工单闭环（M11.10，设计 4.8 流程九；change add-service-order）
--   ①erp_pm_service_order 工单头（登记/定级/SLA/状态机/duplicate_of/NCR/根因/确认）
--   ②erp_pm_service_process_record 处理记录（仅追加，BR-4.8-35）
--   ③erp_pm_service_confirm_token 客户确认免密链接（confirmed_via=LINK）
--   ④erp_pm_service_sla_grade 定级映射配置（类型×影响→等级/时限，FR-4.8-9-2 可配置）
--   ⑤erp_pm_service_sla_log SLA 事件（升级/提醒/默认关闭/挂起恢复，幂等唯一键）
--   ⑥erp_pm_service_survey 满意度回访（1-5 分，FR-4.8-9-5）
--   ⑦erp_pm_service_improve_task 低分改进任务（≤2 分自动生成，BR-4.8-06）
--   ⑧ROLE_CS/ROLE_SERVICE_ENG/ROLE_SERVICE_MGR 角色 + M11-10 菜单 PERM 幂等 UPDATE
--   ⑨erp_inv_material_issue 加 SERVICE_ORDER_NO（售后领料关联，spec material-issue ADDED）
--   ⑩erp_inv_doc_type 加 AFTER_SALE 字典（DocTypeDbTest 计数 17→18）
-- 幂等：ALTER 走 information_schema 守卫（沿 169/174 惯例）+ CREATE IF NOT EXISTS
--      + INSERT IGNORE + UPDATE 只补空
-- ============================================================

-- ---------- 1. 服务工单头（FR-4.8-9-1~4 状态机） ----------

CREATE TABLE IF NOT EXISTS erp_pm_service_order (
    ID                 VARCHAR(32) NOT NULL,
    ORDER_NO           VARCHAR(32) NOT NULL COMMENT 'SO+yyyyMMdd-流水',
    CUSTOMER_ID        VARCHAR(64) NOT NULL COMMENT '客户主数据 erp_mdm_customer_group.ID（口径对齐 SD 域）',
    CUSTOMER_NAME      VARCHAR(200) NULL,
    CONTRACT_NO        VARCHAR(64) NULL COMMENT '关联合同（可选，引用 4.3 合同号）',
    PROJECT_ID         VARCHAR(32) NULL COMMENT '关联项目（可选）',
    COMPLAINT_TYPE     VARCHAR(30) NOT NULL COMMENT 'PRODUCT_QUALITY|DELIVERY_DELAY|SERVICE_ATTITUDE|FUNCTION_DEFECT|OTHER',
    IMPACT_LEVEL       VARCHAR(20) NOT NULL COMMENT 'PRODUCTION 影响生产/安全|CORE 核心功能不可用|NORMAL 一般（定级映射输入）',
    DESCRIPTION        VARCHAR(2000) NOT NULL COMMENT '投诉描述（必填）',
    EXPECT_DEADLINE    DATETIME NULL COMMENT '期望解决时限（可选）',
    ATTACH_IDS         VARCHAR(1000) NULL COMMENT '附件（逗号分隔 BIZ 文件 id）',
    SKILL_TAG          VARCHAR(100) NULL COMMENT '所需技能（文本参考标签，不做主数据）',
    LEVEL              INT NOT NULL DEFAULT 3 COMMENT '投诉等级 1 重大|2 严重|3 一般（auto 定级可人工改，理由留痕）',
    RESP_DEADLINE      DATETIME NULL COMMENT '响应截止=登记时间+响应时限（BR-4.8-03 计时起点=登记）',
    RESOLVE_DEADLINE   DATETIME NULL COMMENT '解决截止（驳回重计时后刷新）',
    STATUS             VARCHAR(20) NOT NULL DEFAULT 'REGISTERED'
                       COMMENT 'REGISTERED|DISPATCHED|PROCESSING|PENDING_CONFIRM|CLOSED',
    ENGINEER_ID        VARCHAR(64) NULL COMMENT '服务工程师（派单/改派）',
    ENGINEER_NAME      VARCHAR(64) NULL,
    RESPONDED_AT       DATETIME NULL COMMENT '首次受理动作时间（响应判定）',
    CONFIRM_SUBMIT_AT  DATETIME NULL COMMENT '提交客户确认时间（解决判定/默认关闭起算）',
    SUSPENDED_FLAG     CHAR(1) NOT NULL DEFAULT '0' COMMENT '1=挂起「待客户信息」，SLA 暂停',
    SUSPEND_AT         DATETIME NULL COMMENT '当前挂起开始时间',
    SUSPEND_HOURS      DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '累计挂起小时（有效耗时=now-登记-累计挂起）',
    ESCALATE_STAGE     INT NOT NULL DEFAULT 0 COMMENT '升级档 0 无|1 至服务主管|2 至销售经理（BR-4.8-03 链路）',
    DUPLICATE_OF       VARCHAR(32) NULL COMMENT '重复投诉关联前单（标注不合并，SLA 独立）',
    NCR_NO             VARCHAR(32) NULL COMMENT '关联质量域 NCR（BR-4.8-36 质量类必填）',
    ROOT_CAUSE         VARCHAR(20) NULL COMMENT 'DESIGN 设计缺陷|MANUFACTURE 制造缺陷|OPERATION 操作不当|EXTERNAL 外部原因',
    ROOT_CURED         CHAR(1) NOT NULL DEFAULT '0' COMMENT '1=已根治（质量类无 NCR 不可置 1，BR-4.8-36）',
    REJECT_COUNT       INT NOT NULL DEFAULT 0 COMMENT '客户驳回次数（≥2 升级服务主管）',
    CONFIRMED_VIA      VARCHAR(10) NULL COMMENT 'AGENT 代录|LINK 门户链接|DEFAULT 默认关闭',
    CONFIRM_RESULT     VARCHAR(20) NULL COMMENT 'RESOLVED|DEFAULT_CLOSED',
    CLOSE_DEFAULT_FLAG CHAR(1) NOT NULL DEFAULT '0' COMMENT '1=默认关闭（5 工作日未确认+2 次提醒后）',
    MATERIAL_COST      DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '服务成本-材料（AFTER_SALE 领料过账累加）',
    REMARK             VARCHAR(500) NULL,
    UPDATE_BY          VARCHAR(64) NULL,
    UPDATE_DATE        DATETIME NULL,
    CREATE_BY          VARCHAR(64) NULL,
    CREATE_DATE        DATETIME NULL,
    DEL_FLAG           CHAR(1) NOT NULL DEFAULT '0',
    VER_NO             INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_PM_SO_NO (ORDER_NO),
    KEY IDX_PM_SO_CUSTOMER (CUSTOMER_ID),
    KEY IDX_PM_SO_STATUS (STATUS),
    KEY IDX_PM_SO_ENGINEER (ENGINEER_ID),
    KEY IDX_PM_SO_NCR (NCR_NO)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服务工单（add-service-order，FR-4.8-9）';

-- ---------- 2. 处理记录（仅追加，服务层不提供改删路径） ----------

CREATE TABLE IF NOT EXISTS erp_pm_service_process_record (
    ID          VARCHAR(32) NOT NULL,
    ORDER_ID    VARCHAR(32) NOT NULL,
    ACTION      VARCHAR(30) NOT NULL COMMENT 'ACCEPT 受理|PROCESS 处理|ROOT_CAUSE 根因|SUSPEND 挂起|RESUME 恢复|REJECT 驳回|SUBMIT_CONFIRM 提交确认|CLOSE 关闭|ESCALATE 升级',
    CONTENT     VARCHAR(2000) NOT NULL COMMENT '处理内容/理由（追加留痕）',
    ATTACH_IDS  VARCHAR(500) NULL,
    UPDATE_BY   VARCHAR(64) NULL,
    UPDATE_DATE DATETIME NULL,
    CREATE_BY   VARCHAR(64) NULL,
    CREATE_DATE DATETIME NULL,
    DEL_FLAG    CHAR(1) NOT NULL DEFAULT '0',
    VER_NO      INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_PM_SPR_ORDER (ORDER_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服务工单处理记录（add-service-order，BR-4.8-35 仅追加）';

-- ---------- 3. 客户确认免密链接 ----------

CREATE TABLE IF NOT EXISTS erp_pm_service_confirm_token (
    ID         VARCHAR(32) NOT NULL,
    ORDER_ID   VARCHAR(32) NOT NULL,
    TOKEN      VARCHAR(64) NOT NULL COMMENT '32 字节随机 hex，一次性',
    EXPIRE_AT  DATETIME NOT NULL COMMENT '默认 5 工作日有效期',
    USED       CHAR(1) NOT NULL DEFAULT '0' COMMENT '1=已使用（一次性）',
    USED_AT    DATETIME NULL,
    UPDATE_BY  VARCHAR(64) NULL,
    UPDATE_DATE DATETIME NULL,
    CREATE_BY  VARCHAR(64) NULL,
    CREATE_DATE DATETIME NULL,
    DEL_FLAG   CHAR(1) NOT NULL DEFAULT '0',
    VER_NO     INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_PM_SO_TOKEN (TOKEN),
    KEY IDX_PM_SO_TOKEN_ORDER (ORDER_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服务工单客户确认链接（add-service-order，confirmed_via=LINK）';

-- ---------- 4. 定级映射配置（FR-4.8-9-2 后台可配；种子=规格默认三级时限） ----------

CREATE TABLE IF NOT EXISTS erp_pm_service_sla_grade (
    ID             VARCHAR(32) NOT NULL,
    COMPLAINT_TYPE VARCHAR(30) NOT NULL DEFAULT '*' COMMENT '投诉类型，* = 通配（精确行优先于通配行）',
    IMPACT_LEVEL   VARCHAR(20) NOT NULL COMMENT 'PRODUCTION|CORE|NORMAL',
    LEVEL          INT NOT NULL COMMENT '定级结果 1/2/3',
    RESP_HOURS     INT NOT NULL COMMENT '响应时限小时：L1=2 L2=4 L3=8',
    RESOLVE_HOURS  INT NOT NULL COMMENT '解决时限小时：L1=24 L2=72 L3=168（7 工作日按 7×24 近似，节假日顺延留 L4）',
    ENABLED        CHAR(1) NOT NULL DEFAULT '1',
    UPDATE_BY      VARCHAR(64) NULL,
    UPDATE_DATE    DATETIME NULL,
    CREATE_BY      VARCHAR(64) NULL,
    CREATE_DATE    DATETIME NULL,
    DEL_FLAG       CHAR(1) NOT NULL DEFAULT '0',
    VER_NO         INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_PM_SO_GRADE (COMPLAINT_TYPE, IMPACT_LEVEL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服务工单定级映射（add-service-order，FR-4.8-9-2 可配置）';

INSERT IGNORE INTO erp_pm_service_sla_grade (ID, COMPLAINT_TYPE, IMPACT_LEVEL, LEVEL, RESP_HOURS, RESOLVE_HOURS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
VALUES ('so-grade-prod', '*', 'PRODUCTION', 1, 2, 24,  'migration', NOW(), '0', 0),
       ('so-grade-core', '*', 'CORE',       2, 4, 72,  'migration', NOW(), '0', 0),
       ('so-grade-norm', '*', 'NORMAL',     3, 8, 168, 'migration', NOW(), '0', 0);

-- ---------- 5. SLA 事件（升级/提醒/默认关闭/挂起恢复；幂等唯一键防重复扫描） ----------

CREATE TABLE IF NOT EXISTS erp_pm_service_sla_log (
    ID             VARCHAR(32) NOT NULL,
    ORDER_ID       VARCHAR(32) NOT NULL,
    EVENT          VARCHAR(30) NOT NULL COMMENT 'ESCALATE 升级|REMIND_CONFIRM 确认提醒|DEFAULT_CLOSE 默认关闭|SUSPEND|RESUME|SURVEY_DUE 回访到期|IMPROVE_OVERDUE 改进逾期',
    ESCALATE_STAGE INT NULL COMMENT 'ESCALATE 时的目标档 1 主管|2 销售经理（组合幂等键）',
    TARGET         VARCHAR(64) NULL COMMENT '升级/提醒对象角色',
    DETAIL         VARCHAR(500) NULL,
    UPDATE_BY      VARCHAR(64) NULL,
    UPDATE_DATE    DATETIME NULL,
    CREATE_BY      VARCHAR(64) NULL,
    CREATE_DATE    DATETIME NULL,
    DEL_FLAG       CHAR(1) NOT NULL DEFAULT '0',
    VER_NO         INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_PM_SO_SLA_LOG (ORDER_ID, EVENT, ESCALATE_STAGE),
    KEY IDX_PM_SO_SLA_ORDER (ORDER_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服务工单 SLA 事件（add-service-order，定时任务幂等）';

-- ---------- 6. 满意度回访（FR-4.8-9-5） ----------

CREATE TABLE IF NOT EXISTS erp_pm_service_survey (
    ID             VARCHAR(32) NOT NULL,
    ORDER_ID       VARCHAR(32) NOT NULL,
    SCORE          INT NULL COMMENT '满意度 1-5 分',
    SUGGESTION     VARCHAR(1000) NULL COMMENT '改进建议',
    CONTACT_TRIES  INT NOT NULL DEFAULT 0 COMMENT '回访尝试次数（3 次失败置未回访）',
    UNREACHED_FLAG CHAR(1) NOT NULL DEFAULT '0' COMMENT '1=未回访归档',
    SURVEY_BY      VARCHAR(64) NULL,
    SURVEY_DATE    DATETIME NULL,
    UPDATE_BY      VARCHAR(64) NULL,
    UPDATE_DATE    DATETIME NULL,
    CREATE_BY      VARCHAR(64) NULL,
    CREATE_DATE    DATETIME NULL,
    DEL_FLAG       CHAR(1) NOT NULL DEFAULT '0',
    VER_NO         INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_PM_SO_SURVEY_ORDER (ORDER_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服务工单满意度回访（add-service-order，FR-4.8-9-5）';

-- ---------- 7. 低分改进任务（BR-4.8-06 ≤2 分自动生成，10 工作日闭环） ----------

CREATE TABLE IF NOT EXISTS erp_pm_service_improve_task (
    ID              VARCHAR(32) NOT NULL,
    ORDER_ID        VARCHAR(32) NOT NULL COMMENT '来源工单',
    CONTENT         VARCHAR(1000) NOT NULL COMMENT '改进要求（低分原因摘要+建议）',
    OWNER_ID        VARCHAR(64) NULL COMMENT '责任人（默认服务主管）',
    OWNER_NAME      VARCHAR(64) NULL,
    DUE_DATE        DATETIME NOT NULL COMMENT '期限=生成+10 工作日',
    STATUS          VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|PROCESSING|CLOSED',
    ESCALATED_SALES CHAR(1) NOT NULL DEFAULT '0' COMMENT '1=已逾期升级销售经理（BR-4.8-06）',
    CLOSE_BY        VARCHAR(64) NULL,
    CLOSE_DATE      DATETIME NULL,
    UPDATE_BY       VARCHAR(64) NULL,
    UPDATE_DATE     DATETIME NULL,
    CREATE_BY       VARCHAR(64) NULL,
    CREATE_DATE     DATETIME NULL,
    DEL_FLAG        CHAR(1) NOT NULL DEFAULT '0',
    VER_NO          INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_PM_SO_IMP_ORDER (ORDER_ID),
    KEY IDX_PM_SO_IMP_STATUS (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服务工单低分改进任务（add-service-order，BR-4.8-06）';

-- ---------- 8. 服务类角色（三角色制，升级链 工程师→主管） ----------

INSERT IGNORE INTO erp_admin_role (ID, ROLE_CODE, ROLE_NAME, SORT_ORDER, STATUS, CREATE_BY, DEL_FLAG, VER_NO)
VALUES ('role-cs',          'ROLE_CS',          '客服',       29, '1', 'system', '0', 0),
       ('role-service-eng', 'ROLE_SERVICE_ENG', '服务工程师', 30, '1', 'system', '0', 0),
       ('role-service-mgr', 'ROLE_SERVICE_MGR', '服务主管',   31, '1', 'system', '0', 0);

-- ---------- 9. M11-10 两叶菜单 PERM（幂等 UPDATE 只补空值；099 已有菜单行） ----------

UPDATE erp_admin_menu SET PERM = 'ROLE_CS,ROLE_SERVICE_MGR,ROLE_SALES_MGR,ROLE_ADMIN'
 WHERE ID = 'M11-10'
   AND (PERM IS NULL OR PERM = '');
UPDATE erp_admin_menu SET PERM = 'ROLE_CS,ROLE_SERVICE_MGR,ROLE_SALES_MGR,ROLE_ADMIN'
 WHERE ID = 'M11-10-1'
   AND (PERM IS NULL OR PERM = '');
UPDATE erp_admin_menu SET PERM = 'ROLE_CS,ROLE_SERVICE_ENG,ROLE_SERVICE_MGR,ROLE_SALES_MGR,ROLE_ADMIN'
 WHERE ID = 'M11-10-2'
   AND (PERM IS NULL OR PERM = '');

-- ---------- 10. 领料单关联服务工单（spec material-issue ADDED：AFTER_SALE） ----------

SET @so_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'erp_inv_material_issue'
                   AND COLUMN_NAME = 'SERVICE_ORDER_NO');
SET @ddl_so := IF(@so_col = 0,
    'ALTER TABLE erp_inv_material_issue ADD COLUMN SERVICE_ORDER_NO VARCHAR(64) NULL COMMENT ''关联服务工单号（AFTER_SALE 必填，add-service-order）''',
    'SELECT 1');
PREPARE stmt_so FROM @ddl_so;
EXECUTE stmt_so;
DEALLOCATE PREPARE stmt_so;

SET @so_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'erp_inv_material_issue'
                   AND INDEX_NAME = 'IDX_MI_SERVICE_ORDER');
SET @ddl_soi := IF(@so_idx = 0,
    'ALTER TABLE erp_inv_material_issue ADD INDEX IDX_MI_SERVICE_ORDER (SERVICE_ORDER_NO)',
    'SELECT 1');
PREPARE stmt_soi FROM @ddl_soi;
EXECUTE stmt_soi;
DEALLOCATE PREPARE stmt_soi;

-- ISSUE_TYPE 列宽扩容（OWN/VMI/MAINT 3 字符 → AFTER_SALE 10 字符）
SET @it_len := (SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'erp_inv_material_issue'
                   AND COLUMN_NAME = 'ISSUE_TYPE');
SET @ddl_it := IF(@it_len < 12,
    'ALTER TABLE erp_inv_material_issue MODIFY COLUMN ISSUE_TYPE VARCHAR(12) NOT NULL DEFAULT ''OWN'' COMMENT ''OWN 自有|VMI 寄售|MAINT 维修|AFTER_SALE 售后''',
    'SELECT 1');
PREPARE stmt_it FROM @ddl_it;
EXECUTE stmt_it;
DEALLOCATE PREPARE stmt_it;

-- 行表 STOCK_TYPE 同步扩容（配批行来源类型跟随头类型）
SET @st_len := (SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'erp_inv_material_issue_line'
                   AND COLUMN_NAME = 'STOCK_TYPE');
SET @ddl_st := IF(@st_len < 12,
    'ALTER TABLE erp_inv_material_issue_line MODIFY COLUMN STOCK_TYPE VARCHAR(12) NOT NULL DEFAULT ''OWN'' COMMENT ''来源库存类型 OWN|VMI|AFTER_SALE''',
    'SELECT 1');
PREPARE stmt_st FROM @ddl_st;
EXECUTE stmt_st;
DEALLOCATE PREPARE stmt_st;

-- ---------- 11. 售后领用单据类型字典（OUT 方向；DocTypeDbTest 计数 17→18） ----------

INSERT IGNORE INTO erp_inv_doc_type (ID, TYPE_CODE, TYPE_NAME, DIRECTION, FLOW_PREFIX, NEED_BATCH, ENABLED, REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
VALUES ('doctype-after-sale', 'AFTER_SALE', '售后领料', 'OUT', 'MAS', 0, 1,
        '服务工单售后换件领料（add-service-order，FR-4.8-9-3）', 'migration', NOW(), '0', 0);
