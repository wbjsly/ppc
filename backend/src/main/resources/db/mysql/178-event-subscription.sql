-- ============================================================
-- 迁移 178：SCM 事件订阅（add-event-subscription，M12.2 四叶）
--   ① erp_intf_event_type 事件定义注册表（Schema/版本/启停）
--   ② erp_intf_subscription 订阅配置（伙伴×事件类型，两级回落）
--   ③ erp_intf_delivery 幂等加列 TARGET_URL / RETRY_MAX（快照语义）
--   ④ 既有 6 类事件种子（SCHEMA_JSON 按 PortalEvents 各构造器反推必填字段）
--   ⑤ ROLE_INTF_ARCH 集成架构师角色（interface-onboarding 既有语义落码）
--   ⑥ M12-2 四叶菜单（自包含 INSERT IGNORE，099 重生成前迁移即可用）
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE + information_schema 判断 ALTER
-- ============================================================

-- ---------- 1. 事件定义注册表（FR-4.9-2-1） ----------

CREATE TABLE IF NOT EXISTS erp_intf_event_type (
    ID             VARCHAR(64)  NOT NULL,
    EVENT_TYPE     VARCHAR(64)  NOT NULL COMMENT '{对象}_{动作} 命名规范，可带域前缀如 PROC.PO_PUSHED',
    TYPE_NAME      VARCHAR(128) NOT NULL,
    SCHEMA_JSON    TEXT         NULL COMMENT '{"required":[{"field":"poNo","type":"string"},...]}',
    VERSION_MAJOR  INT          NOT NULL DEFAULT 1 COMMENT '删必填/改类型/收紧值域须升',
    VERSION_MINOR  INT          NOT NULL DEFAULT 0 COMMENT '增可选字段升',
    STATUS         VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED|DISABLED（停用阻断新发布）',
    REMARK         VARCHAR(512) NULL,
    CREATE_BY      VARCHAR(64)  NULL,
    CREATE_DATE    DATETIME     NULL,
    UPDATE_BY      VARCHAR(64)  NULL,
    UPDATE_DATE    DATETIME     NULL,
    DEL_FLAG       CHAR(1)      NOT NULL DEFAULT '0',
    VER_NO         BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_INTF_ET_TYPE (EVENT_TYPE),
    KEY IDX_INTF_ET_STATUS (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事件定义注册表（add-event-subscription，FR-4.9-2-1）';

-- ---------- 2. 订阅配置（FR-4.9-2-3，两级回落零断流） ----------

CREATE TABLE IF NOT EXISTS erp_intf_subscription (
    ID           VARCHAR(64)  NOT NULL,
    PARTNER_CODE VARCHAR(64)  NOT NULL,
    EVENT_TYPE   VARCHAR(64)  NOT NULL,
    CALLBACK_URL VARCHAR(512) NULL COMMENT '空=回落通道 CALLBACK_URL（零断流）',
    RETRY_MAX    TINYINT      NOT NULL DEFAULT 3 COMMENT '1~5，行级死信阈值（空则全局 API_RETRY_MAX）',
    STATUS       VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED|DISABLED（停用回落通道）',
    REMARK       VARCHAR(512) NULL,
    CREATE_BY    VARCHAR(64)  NULL,
    CREATE_DATE  DATETIME     NULL,
    UPDATE_BY    VARCHAR(64)  NULL,
    UPDATE_DATE  DATETIME     NULL,
    DEL_FLAG     CHAR(1)      NOT NULL DEFAULT '0',
    VER_NO       BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_INTF_SUB_PARTNER_TYPE (PARTNER_CODE, EVENT_TYPE),
    KEY IDX_INTF_SUB_STATUS (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事件订阅配置（add-event-subscription，FR-4.9-2-3）';

-- ---------- 3. 投递行快照列（design D3：建行时固化，防中途换目标半投递） ----------

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_intf_delivery'
        AND COLUMN_NAME = 'TARGET_URL') = 0,
    'ALTER TABLE erp_intf_delivery
       ADD COLUMN TARGET_URL VARCHAR(512) NULL COMMENT ''订阅命中快照（空回落通道 URL）'',
       ADD COLUMN RETRY_MAX TINYINT NULL COMMENT ''订阅命中快照（空用全局 API_RETRY_MAX）''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 4. 既有 6 类事件种子（必填字段按 PortalEvents 构造器反推） ----------

INSERT IGNORE INTO erp_intf_event_type
    (ID, EVENT_TYPE, TYPE_NAME, SCHEMA_JSON, VERSION_MAJOR, VERSION_MINOR, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
VALUES
    ('evt-proc-po-pushed', 'PROC.PO_PUSHED', '采购订单下达推送',
     '{"required":[{"field":"supplierId","type":"string"},{"field":"poNo","type":"string"},{"field":"poType","type":"string"},{"field":"totalAmt","type":"number"}]}',
     1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-po-confirmed', 'PROC.PO_CONFIRMED', '交期确认推送',
     '{"required":[{"field":"supplierId","type":"string"},{"field":"poNo","type":"string"},{"field":"promiseDate","type":"string"},{"field":"confirmSource","type":"string"}]}',
     1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-asn-created', 'PROC.ASN_CREATED', 'ASN 创建推送',
     '{"required":[{"field":"supplierId","type":"string"},{"field":"asnNo","type":"string"},{"field":"poNo","type":"string"}]}',
     1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-vmi-water-synced', 'VMI.WATER_SYNCED', 'VMI 水位同步',
     '{"required":[{"field":"supplierId","type":"string"},{"field":"syncTime","type":"string"},{"field":"items","type":"array"}]}',
     1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    -- 推送与确认共用该类型：必填取两载荷交集（confirm 载荷无 suggestQty，收紧会误伤 L3 拒发）
    ('evt-vmi-replenish-pushed', 'VMI.REPLENISH_PUSHED', '补货建议推送',
     '{"required":[{"field":"supplierId","type":"string"},{"field":"alertId","type":"string"},{"field":"itemCode","type":"string"}]}',
     1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-vmi-settle-pushed', 'VMI.SETTLE_PUSHED', '结算单推送',
     '{"required":[{"field":"supplierId","type":"string"},{"field":"settleNo","type":"string"},{"field":"amount","type":"number"},{"field":"period","type":"string"}]}',
     1, 0, 'ENABLED', 'system', NOW(), '0', 0);

-- 存量接口推送通道事件种子（PROC./VMI. 全集，宽松 Schema 不卡存量；架构师后续按需收紧并升 Major）
INSERT IGNORE INTO erp_intf_event_type
    (ID, EVENT_TYPE, TYPE_NAME, SCHEMA_JSON, VERSION_MAJOR, VERSION_MINOR, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
VALUES
    ('evt-proc-emergency-closed', 'PROC.EMERGENCY.CLOSED', 'PROC.EMERGENCY.CLOSED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-emergency-created', 'PROC.EMERGENCY.CREATED', 'PROC.EMERGENCY.CREATED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-emergency-filled', 'PROC.EMERGENCY.FILLED', 'PROC.EMERGENCY.FILLED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-emergency-overdue', 'PROC.EMERGENCY.OVERDUE', 'PROC.EMERGENCY.OVERDUE', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-emergency-rejected', 'PROC.EMERGENCY.REJECTED', 'PROC.EMERGENCY.REJECTED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-emergency-special_approved', 'PROC.EMERGENCY.SPECIAL_APPROVED', 'PROC.EMERGENCY.SPECIAL_APPROVED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-po-receipt_adjusted', 'PROC.PO.RECEIPT_ADJUSTED', 'PROC.PO.RECEIPT_ADJUSTED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-pr-approved', 'PROC.PR.APPROVED', 'PROC.PR.APPROVED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-pr-closed', 'PROC.PR.CLOSED', 'PROC.PR.CLOSED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-pr-confirmed', 'PROC.PR.CONFIRMED', 'PROC.PR.CONFIRMED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-pr-created', 'PROC.PR.CREATED', 'PROC.PR.CREATED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-pr-rejected', 'PROC.PR.REJECTED', 'PROC.PR.REJECTED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-pr-routed', 'PROC.PR.ROUTED', 'PROC.PR.ROUTED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-pr-submitted', 'PROC.PR.SUBMITTED', 'PROC.PR.SUBMITTED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-rfq-awarded', 'PROC.RFQ.AWARDED', 'PROC.RFQ.AWARDED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-rfq-closed', 'PROC.RFQ.CLOSED', 'PROC.RFQ.CLOSED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-rfq-created', 'PROC.RFQ.CREATED', 'PROC.RFQ.CREATED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-rfq-locked', 'PROC.RFQ.LOCKED', 'PROC.RFQ.LOCKED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-rfq-quoted', 'PROC.RFQ.QUOTED', 'PROC.RFQ.QUOTED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-rfq-quoting', 'PROC.RFQ.QUOTING', 'PROC.RFQ.QUOTING', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-rfq-sent', 'PROC.RFQ.SENT', 'PROC.RFQ.SENT', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-tender-anomaly', 'PROC.TENDER.ANOMALY', 'PROC.TENDER.ANOMALY', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-tender-anomaly_cleared', 'PROC.TENDER.ANOMALY_CLEARED', 'PROC.TENDER.ANOMALY_CLEARED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-tender-awarded', 'PROC.TENDER.AWARDED', 'PROC.TENDER.AWARDED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-tender-award_approved', 'PROC.TENDER.AWARD_APPROVED', 'PROC.TENDER.AWARD_APPROVED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-tender-award_rejected', 'PROC.TENDER.AWARD_REJECTED', 'PROC.TENDER.AWARD_REJECTED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-tender-award_winners', 'PROC.TENDER.AWARD_WINNERS', 'PROC.TENDER.AWARD_WINNERS', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-tender-bidding', 'PROC.TENDER.BIDDING', 'PROC.TENDER.BIDDING', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-tender-cancelled', 'PROC.TENDER.CANCELLED', 'PROC.TENDER.CANCELLED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-tender-created', 'PROC.TENDER.CREATED', 'PROC.TENDER.CREATED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-tender-deadline_postponed', 'PROC.TENDER.DEADLINE_POSTPONED', 'PROC.TENDER.DEADLINE_POSTPONED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-tender-evaluated', 'PROC.TENDER.EVALUATED', 'PROC.TENDER.EVALUATED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-tender-invite_approved', 'PROC.TENDER.INVITE_APPROVED', 'PROC.TENDER.INVITE_APPROVED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-tender-invite_rejected', 'PROC.TENDER.INVITE_REJECTED', 'PROC.TENDER.INVITE_REJECTED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-tender-judges_set', 'PROC.TENDER.JUDGES_SET', 'PROC.TENDER.JUDGES_SET', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-tender-locked', 'PROC.TENDER.LOCKED', 'PROC.TENDER.LOCKED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-tender-objection', 'PROC.TENDER.OBJECTION', 'PROC.TENDER.OBJECTION', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-tender-objection_info', 'PROC.TENDER.OBJECTION_INFO', 'PROC.TENDER.OBJECTION_INFO', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-tender-objection_maintain', 'PROC.TENDER.OBJECTION_MAINTAIN', 'PROC.TENDER.OBJECTION_MAINTAIN', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-tender-objection_rebid', 'PROC.TENDER.OBJECTION_REBID', 'PROC.TENDER.OBJECTION_REBID', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-tender-opened', 'PROC.TENDER.OPENED', 'PROC.TENDER.OPENED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-tender-reg_postponed', 'PROC.TENDER.REG_POSTPONED', 'PROC.TENDER.REG_POSTPONED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-tender-score_amended', 'PROC.TENDER.SCORE_AMENDED', 'PROC.TENDER.SCORE_AMENDED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-tender-suppliers', 'PROC.TENDER.SUPPLIERS', 'PROC.TENDER.SUPPLIERS', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-tender-to_invite', 'PROC.TENDER.TO_INVITE', 'PROC.TENDER.TO_INVITE', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0),
    ('evt-proc-tender-updated', 'PROC.TENDER.UPDATED', 'PROC.TENDER.UPDATED', '{"required":[]}', 1, 0, 'ENABLED', 'system', NOW(), '0', 0);

-- ---------- 5. 集成架构师角色（12.2.1 事件定义维护，interface-onboarding 既有语义落码） ----------

INSERT IGNORE INTO erp_admin_role (ID, ROLE_CODE, ROLE_NAME, SORT_ORDER, STATUS, CREATE_BY, DEL_FLAG, VER_NO)
VALUES ('role-intf-arch', 'ROLE_INTF_ARCH', '集成架构师', 84, '1', 'seed', '0', 0);

-- ---------- 6. M12-2 四叶菜单（自包含 INSERT IGNORE；099 重生成后同 ID 幂等） ----------

INSERT IGNORE INTO erp_admin_menu (ID, PARENT_ID, MENU_CODE, TITLE, PATH, ICON, SORT_ORDER, PERM, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
VALUES ('M12-2',   'M12',     '12.2',   '事件订阅', '',        '',   '20',  '',                          '1', 'system', NOW(), '0', 0),
       ('M12-2-1', 'M12-2',   '12.2.1', '事件定义', '/m/12.2.1', '', '10',  'ROLE_INTF_ARCH,ROLE_ADMIN',  '1', 'system', NOW(), '0', 0),
       ('M12-2-2', 'M12-2',   '12.2.2', '订阅配置', '/m/12.2.2', '', '20',  'ROLE_INTF_OPS,ROLE_ADMIN',   '1', 'system', NOW(), '0', 0),
       ('M12-2-3', 'M12-2',   '12.2.3', '消费监控', '/m/12.2.3', '', '30',  'ROLE_INTF_OPS,ROLE_ADMIN',   '1', 'system', NOW(), '0', 0),
       ('M12-2-4', 'M12-2',   '12.2.4', '告警处理', '/m/12.2.4', '', '40',  'ROLE_INTF_OPS,ROLE_ADMIN',   '1', 'system', NOW(), '0', 0);

-- 幂等补空（与 099 重生成结果冲突时以后者为准，此处只补缺）
UPDATE erp_admin_menu SET PERM = 'ROLE_INTF_ARCH,ROLE_ADMIN'
 WHERE ID = 'M12-2-1' AND (PERM IS NULL OR PERM = '');
UPDATE erp_admin_menu SET PERM = 'ROLE_INTF_OPS,ROLE_ADMIN'
 WHERE ID IN ('M12-2-2', 'M12-2-3', 'M12-2-4') AND (PERM IS NULL OR PERM = '');
