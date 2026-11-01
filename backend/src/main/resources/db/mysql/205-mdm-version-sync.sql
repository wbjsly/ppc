-- 205: MDM 版本中心与事件同步（add-mdm-version-sync）
-- 1.7 版本管理 / 1.8 集成同步：内部监听执行台账 + MDM 事件类型注册（菜单 1.7.1~1.8.2 已存在且 PATH 已挂）
-- 幂等：可重复执行

-- 1) 内部监听执行台账（1.8.2 内部 Tab 数据源）
CREATE TABLE IF NOT EXISTS erp_mdm_listener_log (
    ID             varchar(64)  NOT NULL PRIMARY KEY,
    EVENT_ID       varchar(64)  NOT NULL COMMENT '内部事件 UUID（与 outbox EVENT_ID 对齐）',
    EVENT_TYPE     varchar(64)  NOT NULL COMMENT 'MDM.ITEM.UPDATED 等',
    ENTITY_ID      varchar(64)  NULL,
    LISTENER_NAME  varchar(64)  NOT NULL COMMENT 'priceRecalcMark / creditRefresh / taxSyncHint',
    STATUS         varchar(16)  NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/RETRYING/FAILED',
    RETRY_CNT      int          NOT NULL DEFAULT 0,
    EXEC_MS        int          NULL,
    ERROR_MSG      varchar(500) NULL,
    WORK_TICKET_ID varchar(64)  NULL COMMENT '3 次失败关联 erp_intf_ticket.ID',
    CREATE_BY      varchar(64)  NULL,
    CREATE_DATE    datetime     NULL DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG       varchar(1)   NOT NULL DEFAULT '0',
    VER_NO         int          NOT NULL DEFAULT 0,
    KEY IDX_MDM_LISTENER_EVENT (EVENT_ID),
    KEY IDX_MDM_LISTENER_STATUS (STATUS),
    KEY IDX_MDM_LISTENER_TYPE (EVENT_TYPE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MDM 下游监听执行台账';

-- 2) MDM 事件类型注册（1.8.1 变更事件页展示名；MDM.* 免发布校验，注册供台账/订阅参考）
-- 13 TYPE（对齐 13 张版本表）× {CREATED, UPDATED, DISABLED} + SUPPLIER MERGED = 40
-- 清理历史误注册（CUSTOMER 主表不存在，早期版本曾误写 MDM.CUSTOMER.*）
UPDATE erp_intf_event_type SET DEL_FLAG='1', UPDATE_DATE=NOW()
WHERE EVENT_TYPE LIKE 'MDM.CUSTOMER.%' AND DEL_FLAG='0';

INSERT IGNORE INTO erp_intf_event_type (ID, EVENT_TYPE, TYPE_NAME, VERSION_MAJOR, VERSION_MINOR, STATUS, REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
SELECT CONCAT('mdm-et-', t.n, '-', o.n), CONCAT('MDM.', t.n, '.', o.n), CONCAT(t.c, '主数据', o.c), 1, 0, 'ENABLED', 'MDM 事件同步（add-mdm-version-sync）', 'migration-205', NOW(), '0', 0
FROM (SELECT 1 n, 'ITEM' x, '物料' c UNION SELECT 2,'CUSTOMER_GROUP','客户组' UNION SELECT 3,'CUSTOMER_VIEW','客户视图'
      UNION SELECT 4,'SUPPLIER','供应商' UNION SELECT 5,'EXCHANGE_RATE','汇率' UNION SELECT 6,'TAX_CODE','税码'
      UNION SELECT 7,'ORG_UNIT','组织' UNION SELECT 8,'COST_CENTER','成本中心' UNION SELECT 9,'PROFIT_CENTER','利润中心'
      UNION SELECT 10,'LEGAL_ENTITY','法人' UNION SELECT 11,'ITEM_CATEGORY','物料分类' UNION SELECT 12,'PRICE_AGREEMENT','价格协议'
      UNION SELECT 13,'TAX_POLICY','税策') t
CROSS JOIN (SELECT 'CREATED' n, '创建' c UNION SELECT 'UPDATED','变更' UNION SELECT 'DISABLED','停用') o
WHERE NOT EXISTS (SELECT 1 FROM erp_intf_event_type e WHERE e.EVENT_TYPE = CONCAT('MDM.', t.n, '.', o.n) AND e.DEL_FLAG='0');

INSERT IGNORE INTO erp_intf_event_type (ID, EVENT_TYPE, TYPE_NAME, VERSION_MAJOR, VERSION_MINOR, STATUS, REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
SELECT CONCAT('mdm-et-merge-', x.n), CONCAT('MDM.', x.x, '.MERGED'), CONCAT(x.c, '主数据合并'), 1, 0, 'ENABLED', 'MDM 事件同步（add-mdm-version-sync）', 'migration-205', NOW(), '0', 0
FROM (SELECT 4 n, 'SUPPLIER' x, '供应商' c) x
WHERE NOT EXISTS (SELECT 1 FROM erp_intf_event_type e WHERE e.EVENT_TYPE = CONCAT('MDM.', x.x, '.MERGED') AND e.DEL_FLAG='0');
