-- ============================================================
-- 186-role-permission.sql
-- change: add-role-permission-console（16.2 角色权限，design D1）
--   三张新表：
--     erp_admin_menu_perm_override   权限矩阵覆盖层（仅存差异格，绝对指令语义）
--     erp_admin_position_baseline    岗位基线（系统域自有，替代 hr_position.ROLES_JSON）
--     erp_admin_role_grant_request   超基线授予例外申请单（冻结待生效载荷）
--   存量处理：拷入非空 ROLES_JSON → 剥离全部特权角色（1569 行占位 ["ROLE_HR"]
--   剥离后集合为空——新账号建号零角色，直至 16.2.3 配置真基线，BREAKING 见 proposal）
--   特权清单（D3，与 PrivilegedRoles 常量同源）= ApprovalNodeSpec.sign() 全部 24 枚：
--     ADMIN/ASSET_ADMIN/COMPLIANCE/DEPT_HEAD/EQUIP_MGR/EXEC/FINANCE/FINANCE_MGR/
--     FIN_ASSET/FIN_MGR/GM/HR/HR_MGR/PLAN_MGR/PM/PMGR/PMO/PROCESS_MGR/PURCHASER/
--     QUALITY_MGR/SALES_DIRECTOR/SALES_MGR/TECH_OWNER/WAREHOUSE
--     （金额角色 FINANCE*/FIN_*/GM 已全部含于 sign 清单，无额外项）
-- ============================================================
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE + 剥离 UPDATE 可重跑
-- ============================================================

CREATE TABLE IF NOT EXISTS erp_admin_menu_perm_override (
  ID            VARCHAR(32)  NOT NULL COMMENT '主键',
  MENU_ID       VARCHAR(64)  NOT NULL COMMENT '菜单节点 ID（erp_admin_menu.ID）',
  ROLE_CODE     VARCHAR(64)  NOT NULL COMMENT '角色码',
  ACTION        VARCHAR(8)   NOT NULL COMMENT 'ADD=强制包含 / REMOVE=强制排除（绝对指令）',
  CREATE_BY     VARCHAR(64)  DEFAULT NULL,
  CREATE_DATE   DATETIME     DEFAULT NULL,
  UPDATE_BY     VARCHAR(64)  DEFAULT NULL,
  UPDATE_DATE   DATETIME     DEFAULT NULL,
  DEL_FLAG      VARCHAR(1)   NOT NULL DEFAULT '0' COMMENT '逻辑删除（清除覆盖=物理删，见 design D2）',
  VER_NO        INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (ID),
  UNIQUE KEY UK_PERM_OVR_CELL (MENU_ID, ROLE_CODE),
  KEY IDX_PERM_OVR_ROLE (ROLE_CODE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单权限矩阵覆盖层（099 基线 ∪ override）';

CREATE TABLE IF NOT EXISTS erp_admin_position_baseline (
  ID            VARCHAR(32)  NOT NULL COMMENT '主键',
  POS_ID        VARCHAR(32)  NOT NULL COMMENT '岗位 ID（hr_position.ID）',
  ROLE_CODES    VARCHAR(1024) NOT NULL DEFAULT '[]' COMMENT '角色码 JSON 数组（非特权角色）',
  CREATE_BY     VARCHAR(64)  DEFAULT NULL,
  CREATE_DATE   DATETIME     DEFAULT NULL,
  UPDATE_BY     VARCHAR(64)  DEFAULT NULL,
  UPDATE_DATE   DATETIME     DEFAULT NULL,
  DEL_FLAG      VARCHAR(1)   NOT NULL DEFAULT '0',
  VER_NO        INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (ID),
  UNIQUE KEY UK_POS_BASE (POS_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='岗位权限基线（建号/调岗角色映射唯一来源）';

CREATE TABLE IF NOT EXISTS erp_admin_role_grant_request (
  ID               VARCHAR(32)  NOT NULL COMMENT '主键（= 审批 BIZ_ID）',
  USER_ID          VARCHAR(32)  NOT NULL COMMENT '目标用户（erp_admin_user.ID）',
  USERNAME         VARCHAR(64)  DEFAULT NULL COMMENT '目标用户名（快照，审计可读）',
  TARGET_ROLES     VARCHAR(1024) NOT NULL COMMENT '目标角色全集 JSON 数组',
  BEYOND_ROLES     VARCHAR(1024) NOT NULL COMMENT '超基线子集 JSON 数组（审批焦点）',
  BASE_ROLES       VARCHAR(1024) DEFAULT NULL COMMENT '提交时岗位基线快照 JSON',
  REASON           VARCHAR(500) DEFAULT NULL COMMENT '例外理由（提交必填）',
  STATUS           VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/CANCELLED',
  APPR_INSTANCE_ID VARCHAR(32)  DEFAULT NULL COMMENT '审批实例 ID（erp_approval_instance.ID）',
  RESOLVE_OPINION  VARCHAR(500) DEFAULT NULL COMMENT '审批意见快照',
  CREATE_BY        VARCHAR(64)  DEFAULT NULL,
  CREATE_DATE      DATETIME     DEFAULT NULL,
  UPDATE_BY        VARCHAR(64)  DEFAULT NULL,
  UPDATE_DATE      DATETIME     DEFAULT NULL,
  DEL_FLAG         VARCHAR(1)   NOT NULL DEFAULT '0',
  VER_NO           INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (ID),
  KEY IDX_GRANT_USER (USER_ID, STATUS),
  KEY IDX_GRANT_APR (APPR_INSTANCE_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='超基线授予例外申请单（BR-5.6-03）';

-- ============================================================
-- 2. 存量拷入：非空 ROLES_JSON → 基线表（ID 由 POS_ID 派生，幂等）
-- ============================================================

INSERT IGNORE INTO erp_admin_position_baseline
  (ID, POS_ID, ROLE_CODES, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
SELECT CONCAT('posb-', p.ID), p.ID, p.ROLES_JSON, 'migrate-186', NOW(), '0', 0
FROM hr_position p
WHERE p.ROLES_JSON IS NOT NULL AND p.ROLES_JSON <> ''
  AND p.DEL_FLAG = '0';

-- ============================================================
-- 3. 特权剥离（L1，幂等）：JSON_TABLE 展开 → 过滤特权码 → 重聚合成数组
--    已干净的行（'[]'/空）跳过；全被剥空的行落 '[]'（行保留，spec 场景
--    "基线行保留但特权角色被剥离"）
-- ============================================================

UPDATE erp_admin_position_baseline b
SET ROLE_CODES = COALESCE((
      SELECT JSON_ARRAYAGG(t.rc)
      FROM JSON_TABLE(b.ROLE_CODES, '$[*]'
        COLUMNS (rc VARCHAR(64) PATH '$')) t
      WHERE t.rc NOT IN (
        'ROLE_ADMIN','ROLE_ASSET_ADMIN','ROLE_COMPLIANCE','ROLE_DEPT_HEAD',
        'ROLE_EQUIP_MGR','ROLE_EXEC','ROLE_FINANCE','ROLE_FINANCE_MGR',
        'ROLE_FIN_ASSET','ROLE_FIN_MGR','ROLE_GM','ROLE_HR','ROLE_HR_MGR',
        'ROLE_PLAN_MGR','ROLE_PM','ROLE_PMGR','ROLE_PMO','ROLE_PROCESS_MGR',
        'ROLE_PURCHASER','ROLE_QUALITY_MGR','ROLE_SALES_DIRECTOR',
        'ROLE_SALES_MGR','ROLE_TECH_OWNER','ROLE_WAREHOUSE'
      )
    ), '[]'),
    UPDATE_BY = 'migrate-186',
    UPDATE_DATE = NOW()
WHERE b.ROLE_CODES IS NOT NULL
  AND b.ROLE_CODES NOT IN ('', '[]')
  AND b.DEL_FLAG = '0';
