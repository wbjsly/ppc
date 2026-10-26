-- ============================================================
-- 168 项目立项（add-project-initiation）
--   ①erp_pm_project 项目头 ②erp_pm_wbs WBS 三层树
--   ③erp_pm_budget_line 任务级预算行 ④erp_pm_budget_change 预算变更单
--   ⑤erp_pm_rate 岗位费率 ⑥erp_pm_capacity 产能日历
--   ⑦erp_pm_wbs_template WBS 模板 ⑧ROLE_PMO 角子 ⑨M11-3 菜单 PERM
--   菜单 PERM 沿 167 教训：099 仅 INSERT IGNORE + marker 跳过，幂等 UPDATE 只补空值
-- ============================================================

-- ---------- 1. 项目头 ----------

CREATE TABLE IF NOT EXISTS erp_pm_project (
    ID             VARCHAR(32) NOT NULL,
    PROJECT_NO     VARCHAR(20) NULL COMMENT 'PRJ+yyyy+4位流水，基线审批通过时生成，生成后不可改',
    NAME           VARCHAR(128) NOT NULL,
    PROJ_TYPE      VARCHAR(20) NOT NULL COMMENT '研发/实施/运维',
    DELIVERY_PATH  VARCHAR(10) NOT NULL COMMENT 'EXT 外部交付 / INTERNAL 内部项目',
    CONTRACT_ID    VARCHAR(32) NULL COMMENT 'EXT 必填，关联 erp_crm_contract',
    CONTRACT_NO    VARCHAR(32) NULL,
    INTERNAL_FORM_NO VARCHAR(64) NULL COMMENT 'INTERNAL 必填，内部立项审批单号（自由文本）',
    PM_USER_ID     VARCHAR(32) NOT NULL COMMENT '项目经理（登录账号）',
    PM_NAME        VARCHAR(64) NULL,
    PMO_USER_ID    VARCHAR(32) NULL,
    START_DATE     DATE NOT NULL,
    END_DATE       DATE NOT NULL,
    GOAL           VARCHAR(1000) NULL COMMENT '项目目标',
    SCOPE          VARCHAR(2000) NULL COMMENT '项目范围',
    STATUS         VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/APPROVING/ACTIVE',
    APPR_ID        VARCHAR(32) NULL COMMENT '基线审批实例',
    REJECT_OPINION VARCHAR(500) NULL,
    BASELINE_LOCKED CHAR(1) NOT NULL DEFAULT '0' COMMENT '1=基线锁定（审批通过）',
    BASELINE_AMOUNT DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '基线金额合计',
    FENPLAN_RATE   DECIMAL(6,4) NOT NULL DEFAULT 0.1000 COMMENT '间接分摊率',
    RATE_OVERRIDE  VARCHAR(500) NULL COMMENT '项目级费率覆盖 JSON {grade:rate}',
    START_NOTIFIED CHAR(1) NOT NULL DEFAULT '0',
    CREATE_BY      VARCHAR(64) NULL,
    CREATE_DATE    DATETIME NULL,
    UPDATE_BY      VARCHAR(64) NULL,
    UPDATE_DATE    DATETIME NULL,
    DEL_FLAG       CHAR(1) NOT NULL DEFAULT '0',
    VER_NO         INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_PM_PROJECT_NO (PROJECT_NO),
    KEY IDX_PM_PROJECT_STATUS (STATUS),
    KEY IDX_PM_PROJECT_PM (PM_USER_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目立项';

-- ---------- 2. WBS 单表三层树 ----------

CREATE TABLE IF NOT EXISTS erp_pm_wbs (
    ID             VARCHAR(32) NOT NULL,
    PROJECT_ID     VARCHAR(32) NOT NULL,
    WBS_CODE       VARCHAR(50) NOT NULL COMMENT '层级编码 如 1.2.3',
    PARENT_ID      VARCHAR(32) NULL,
    LEVEL          INT NOT NULL COMMENT '1 阶段 / 2 工作包 / 3 任务',
    NAME           VARCHAR(200) NOT NULL,
    OWNER_USER_ID  VARCHAR(32) NULL COMMENT '负责人（任务级用于费率）',
    OWNER_NAME     VARCHAR(64) NULL,
    OWNER_GRADE    VARCHAR(32) NULL COMMENT '职级（快照，费率计算）',
    PLAN_START     DATE NULL,
    PLAN_END       DATE NULL,
    EST_HOURS      DECIMAL(10,2) NULL COMMENT '预估工时',
    EST_COST       DECIMAL(14,2) NULL COMMENT '预估成本（展示用）',
    DELIVERABLE    VARCHAR(500) NULL COMMENT '交付物定义（level2 必填）',
    SORT_NO        INT NOT NULL DEFAULT 0,
    CREATE_BY      VARCHAR(64) NULL,
    CREATE_DATE    DATETIME NULL,
    UPDATE_BY      VARCHAR(64) NULL,
    UPDATE_DATE    DATETIME NULL,
    DEL_FLAG       CHAR(1) NOT NULL DEFAULT '0',
    VER_NO         INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    -- 168 修订：原 UNIQUE(PROJECT_ID,WBS_CODE) 与 @TableLogic 逻辑删除不兼容
    -- （模板覆盖/删节点重建会以同码复用，已删行仍占唯一键）→ 降级普通索引，
    -- 活行唯一性由 nextPhaseCode/nextChildCode 应用层 max+1 保证
    KEY IDX_PM_WBS_CODE (PROJECT_ID, WBS_CODE),
    KEY IDX_PM_WBS_PROJECT (PROJECT_ID),
    KEY IDX_PM_WBS_PARENT (PARENT_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='WBS 分解';

-- ---------- 3. 任务级预算行 ----------

CREATE TABLE IF NOT EXISTS erp_pm_budget_line (
    ID           VARCHAR(32) NOT NULL,
    PROJECT_ID   VARCHAR(32) NOT NULL,
    WBS_ID       VARCHAR(32) NOT NULL COMMENT '任务级（level3）',
    LABOR        DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '人工=工时×费率（服务端算）',
    MATERIAL     DECIMAL(14,2) NOT NULL DEFAULT 0,
    SUBCONTRACT  DECIMAL(14,2) NOT NULL DEFAULT 0,
    TRAVEL       DECIMAL(14,2) NOT NULL DEFAULT 0,
    OTHER        DECIMAL(14,2) NOT NULL DEFAULT 0,
    INDIRECT     DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '间接=前五项×分摊率（服务端算）',
    CREATE_BY    VARCHAR(64) NULL,
    CREATE_DATE  DATETIME NULL,
    UPDATE_BY    VARCHAR(64) NULL,
    UPDATE_DATE  DATETIME NULL,
    DEL_FLAG     CHAR(1) NOT NULL DEFAULT '0',
    VER_NO       INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_PM_BUDGET_WBS (WBS_ID),
    KEY IDX_PM_BUDGET_PROJECT (PROJECT_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务级预算行';

-- ---------- 4. 预算变更单 ----------

CREATE TABLE IF NOT EXISTS erp_pm_budget_change (
    ID             VARCHAR(32) NOT NULL,
    CHANGE_NO      VARCHAR(32) NOT NULL COMMENT 'PBC+yyyyMMdd-序号',
    PROJECT_ID     VARCHAR(32) NOT NULL,
    REASON         VARCHAR(1000) NOT NULL,
    BEFORE_AMOUNT  DECIMAL(14,2) NOT NULL,
    AFTER_AMOUNT   DECIMAL(14,2) NOT NULL,
    SNAPSHOT_JSON  LONGTEXT NOT NULL COMMENT '变更后预算行快照',
    STATUS         VARCHAR(20) NOT NULL COMMENT 'PENDING/APPROVED/REJECTED',
    APPR_ID        VARCHAR(32) NULL,
    PM_OPINION     VARCHAR(500) NULL,
    FIN_OPINION    VARCHAR(500) NULL,
    CREATE_BY      VARCHAR(64) NULL,
    CREATE_DATE    DATETIME NULL,
    UPDATE_BY      VARCHAR(64) NULL,
    UPDATE_DATE    DATETIME NULL,
    DEL_FLAG       CHAR(1) NOT NULL DEFAULT '0',
    VER_NO         INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_PM_CHANGE_NO (CHANGE_NO),
    KEY IDX_PM_CHANGE_PROJECT (PROJECT_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预算变更单';

-- ---------- 5. 岗位费率 ----------

CREATE TABLE IF NOT EXISTS erp_pm_rate (
    ID           VARCHAR(32) NOT NULL,
    GRADE        VARCHAR(32) NOT NULL COMMENT '职级 CODE',
    GRADE_NAME   VARCHAR(64) NULL,
    RATE_PER_HOUR DECIMAL(10,2) NOT NULL,
    STATUS       VARCHAR(1) NOT NULL DEFAULT '1',
    CREATE_BY    VARCHAR(64) NULL,
    CREATE_DATE  DATETIME NULL,
    UPDATE_BY    VARCHAR(64) NULL,
    UPDATE_DATE  DATETIME NULL,
    DEL_FLAG     CHAR(1) NOT NULL DEFAULT '0',
    VER_NO       INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_PM_RATE_GRADE (GRADE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='岗位费率';

INSERT IGNORE INTO erp_pm_rate (ID, GRADE, GRADE_NAME, RATE_PER_HOUR, STATUS, CREATE_BY, DEL_FLAG, VER_NO)
VALUES ('pmrate-junior', 'JUNIOR', '初级', 60, '1', 'system', '0', 0),
       ('pmrate-mid',    'MID',    '中级', 100, '1', 'system', '0', 0),
       ('pmrate-senior', 'SENIOR', '高级', 160, '1', 'system', '0', 0);

-- ---------- 6. 产能日历 ----------

CREATE TABLE IF NOT EXISTS erp_pm_capacity (
    ID         VARCHAR(32) NOT NULL,
    USER_ID    VARCHAR(32) NOT NULL,
    CAP_DATE   DATE NOT NULL,
    HOURS      DECIMAL(5,2) NOT NULL DEFAULT 8 COMMENT '默认工作日 8，周末 0',
    REMARK     VARCHAR(200) NULL,
    CREATE_BY  VARCHAR(64) NULL,
    CREATE_DATE DATETIME NULL,
    UPDATE_BY  VARCHAR(64) NULL,
    UPDATE_DATE DATETIME NULL,
    DEL_FLAG   CHAR(1) NOT NULL DEFAULT '0',
    VER_NO     INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_PM_CAPACITY (USER_ID, CAP_DATE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人员产能日历';

-- ---------- 7. WBS 模板 ----------

CREATE TABLE IF NOT EXISTS erp_pm_wbs_template (
    ID           VARCHAR(32) NOT NULL,
    TEMPLATE_NO  VARCHAR(32) NOT NULL,
    NAME         VARCHAR(128) NOT NULL,
    PROJ_TYPE    VARCHAR(20) NOT NULL,
    STATUS       VARCHAR(1) NOT NULL DEFAULT '1' COMMENT '1 启用 / 0 停用',
    TEMPLATE_JSON LONGTEXT NOT NULL COMMENT '三层结构 [{n,l,d,h,c:[...]}] 短键',
    CREATE_BY    VARCHAR(64) NULL,
    CREATE_DATE  DATETIME NULL,
    UPDATE_BY    VARCHAR(64) NULL,
    UPDATE_DATE  DATETIME NULL,
    DEL_FLAG     CHAR(1) NOT NULL DEFAULT '0',
    VER_NO       INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_PM_TEMPLATE_NO (TEMPLATE_NO)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='WBS 模板';

INSERT IGNORE INTO erp_pm_wbs_template (ID, TEMPLATE_NO, NAME, PROJ_TYPE, STATUS, TEMPLATE_JSON, CREATE_BY, DEL_FLAG, VER_NO)
VALUES
('pmtpl-rd', 'TPL-RD', '研发项目标准模板', '研发', '1',
 '[{"n":"需求分析","l":1,"c":[{"n":"需求调研","l":2,"d":"需求规格说明书","c":[{"n":"用户访谈","l":3,"h":40},{"n":"需求文档编写","l":3,"h":32}]},{"n":"需求评审","l":2,"d":"评审纪要","c":[{"n":"评审组织","l":3,"h":16}]}]},{"n":"设计开发","l":1,"c":[{"n":"概要设计","l":2,"d":"概要设计说明书","c":[{"n":"架构设计","l":3,"h":64},{"n":"接口设计","l":3,"h":40}]},{"n":"编码实现","l":2,"d":"代码与单元测试报告","c":[{"n":"模块开发","l":3,"h":240},{"n":"单元测试","l":3,"h":80}]}]},{"n":"测试验收","l":1,"c":[{"n":"系统测试","l":2,"d":"测试报告","c":[{"n":"测试用例设计","l":3,"h":48},{"n":"测试执行","l":3,"h":96}]},{"n":"验收交付","l":2,"d":"验收报告","c":[{"n":"用户验收","l":3,"h":40}]}]}]',
 'system', '0', 0),
('pmtpl-impl', 'TPL-IMPL', '实施项目标准模板', '实施', '1',
 '[{"n":"项目启动","l":1,"c":[{"n":"启动准备","l":2,"d":"项目章程","c":[{"n":" kickoff 会议","l":3,"h":16},{"n":"计划编制","l":3,"h":24}]}]},{"n":"蓝图设计","l":1,"c":[{"n":"现状调研","l":2,"d":"调研报告","c":[{"n":"业务访谈","l":3,"h":48}]},{"n":"方案设计","l":2,"d":"蓝图方案","c":[{"n":"方案编写","l":3,"h":64},{"n":"方案评审","l":3,"h":16}]}]},{"n":"系统实现","l":1,"c":[{"n":"配置开发","l":2,"d":"配置清单/二次开发件","c":[{"n":"系统配置","l":3,"h":120},{"n":"二次开发","l":3,"h":160}]}]},{"n":"上线切换","l":1,"c":[{"n":"UAT 与培训","l":2,"d":"UAT 报告/培训记录","c":[{"n":"UAT 支持","l":3,"h":64},{"n":"用户培训","l":3,"h":32}]},{"n":"切换上线","l":2,"d":"上线报告","c":[{"n":"数据迁移","l":3,"h":48},{"n":"上线保驾","l":3,"h":40}]}]}]',
 'system', '0', 0),
('pmtpl-ops', 'TPL-OPS', '运维项目标准模板', '运维', '1',
 '[{"n":"运维准备","l":1,"c":[{"n":"服务开通","l":2,"d":"服务开通单","c":[{"n":"环境准备","l":3,"h":24},{"n":"监控接入","l":3,"h":16}]}]},{"n":"日常运维","l":1,"c":[{"n":"监控值守","l":2,"d":"月度运维报告","c":[{"n":"巡检执行","l":3,"h":80},{"n":"事件处理","l":3,"h":120}]},{"n":"优化改进","l":2,"d":"优化建议书","c":[{"n":"性能优化","l":3,"h":48}]}]}]',
 'system', '0', 0);

-- ---------- 8. PMO 角色 ----------

INSERT IGNORE INTO erp_admin_role (ID, ROLE_CODE, ROLE_NAME, SORT_ORDER, STATUS, CREATE_BY, DEL_FLAG, VER_NO)
VALUES ('role-pmo', 'ROLE_PMO', '项目管理办公室', 25, '1', 'system', '0', 0);

-- ---------- 9. M11-3 菜单 PERM（幂等 UPDATE 只补空值） ----------

UPDATE erp_admin_menu SET PERM = 'ROLE_PMO,ROLE_PM,ROLE_ADMIN'
 WHERE ID IN ('M11-3', 'M11-3-1', 'M11-3-2') AND (PERM IS NULL OR PERM = '');
UPDATE erp_admin_menu SET PERM = 'ROLE_PMO,ROLE_PM,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN'
 WHERE ID = 'M11-3-3' AND (PERM IS NULL OR PERM = '');
