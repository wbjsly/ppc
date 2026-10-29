-- ============================================================
-- 191-audit-log-console.sql
-- change: add-audit-log-console（16.7 审计日志，BR-5.6-13/14、S-5.6-07）
--   1. hr_change_log 受控 ALTER 加 SOURCE_IP/TERMINAL（D2/D3）
--   2. 乱码行修复（latin1 历史残留；本轮核查当前 0 条，幂等 UPDATE 占位）
--   3. 尝试记录表 erp_ops_audit_tamper
--      ★ D1 修订：ENGINE=MyISAM —— 触发器内 INSERT 后 SIGNAL 抛错会回滚
--        同事务的 InnoDB 写入，「拒绝+留痕」无法同时成立；MyISAM 非事务型，
--        INSERT 在 SIGNAL 回滚后仍持久化（低频安全事件记录，可接受无事务）。
--   4. DELETE/UPDATE 两触发器（@audit_bypass 会话豁免；豁免放行仍写尝试记录）
--   5. 脱敏规则表 erp_ops_mask_rule + 身份证/银行卡/手机 3 种子（D6）
--   6. 取证申请 erp_ops_forensic_request（内审+DPO 双签两组字段，D5）
--      + 取证包 erp_ops_forensic_package（封条 JSON/过期/状态，D4/D7）
--   7. ROLE_DPO 数据保护官角色 + 授予 admin（冒烟/测试兜底）
-- ============================================================
-- 幂等：受控 ALTER（information_schema+PREPARE）+ CREATE TABLE IF NOT EXISTS
--       + 触发器 DROP IF EXISTS 后重建 + INSERT IGNORE，可重跑
-- ============================================================

-- ---- 1. hr_change_log 加列（幂等受控 ALTER）----
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='hr_change_log' AND COLUMN_NAME='SOURCE_IP');
SET @s := IF(@c=0, 'ALTER TABLE hr_change_log ADD COLUMN SOURCE_IP VARCHAR(64) DEFAULT NULL COMMENT ''来源 IP（S-5.6-07 取证包字段，渐进覆盖）'' AFTER CHANGE_AT', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='hr_change_log' AND COLUMN_NAME='TERMINAL');
SET @s := IF(@c=0, 'ALTER TABLE hr_change_log ADD COLUMN TERMINAL VARCHAR(100) DEFAULT NULL COMMENT ''终端标识 User-Agent 前 100 字符'' AFTER SOURCE_IP', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- 2. 乱码行修复（幂等；原值不可恢复，置占位标注不伪造）----
-- 本轮核查（2026-10-29）FIELD_NAME 已无 '%?%' 行（48 条历史污染已在早前修复），语句保留为幂等占位
UPDATE hr_change_log SET FIELD_NAME='(历史乱码)' WHERE FIELD_NAME LIKE '%?%';

-- ---- 3. 尝试记录表（MyISAM：见文件头 D1 修订说明）----
CREATE TABLE IF NOT EXISTS erp_ops_audit_tamper (
  ID             VARCHAR(32)  NOT NULL COMMENT '主键（触发器内 UUID）',
  ACTION_TYPE    VARCHAR(10)  NOT NULL COMMENT 'UPDATE / DELETE',
  EMP_ID         VARCHAR(32)  DEFAULT NULL COMMENT '受影响对象',
  FIELD_NAME     VARCHAR(64)  DEFAULT NULL COMMENT '受影响动作',
  OLD_VALUE      VARCHAR(500) DEFAULT NULL,
  NEW_VALUE      VARCHAR(500) DEFAULT NULL,
  CHANGE_BY      VARCHAR(64)  DEFAULT NULL COMMENT '尝试操作者',
  BYPASS         CHAR(1)      NOT NULL DEFAULT '0' COMMENT '1=会话豁免放行（仍留痕）/ 0=已拒绝',
  ATTEMPT_AT     DATETIME     NOT NULL COMMENT '尝试时间',
  PRIMARY KEY (ID),
  KEY IDX_AUDIT_TAMPER_AT (ATTEMPT_AT)
) ENGINE=MyISAM DEFAULT CHARSET=utf8mb4 COMMENT='审计日志删改尝试记录（BR-5.6-13，触发器驱动；MyISAM 保证 SIGNAL 回滚后留痕）';

-- ---- 4. 两触发器（DROP IF EXISTS 后重建 = 幂等；DELIMITER $$ 使体内分号不被切分）----
DELIMITER $$
DROP TRIGGER IF EXISTS trg_hr_change_log_no_update$$
CREATE TRIGGER trg_hr_change_log_no_update BEFORE UPDATE ON hr_change_log
FOR EACH ROW
BEGIN
  INSERT INTO erp_ops_audit_tamper (ID, ACTION_TYPE, EMP_ID, FIELD_NAME, OLD_VALUE, NEW_VALUE, CHANGE_BY, BYPASS, ATTEMPT_AT)
  VALUES (REPLACE(UUID(),'-',''), 'UPDATE', OLD.EMP_ID, OLD.FIELD_NAME, OLD.OLD_VALUE, NEW.NEW_VALUE, USER(), IF(COALESCE(@audit_bypass,0)=1,'1','0'), NOW());
  IF COALESCE(@audit_bypass, 0) <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '审计日志禁止修改（BR-5.6-13）';
  END IF;
END$$

DROP TRIGGER IF EXISTS trg_hr_change_log_no_delete$$
CREATE TRIGGER trg_hr_change_log_no_delete BEFORE DELETE ON hr_change_log
FOR EACH ROW
BEGIN
  INSERT INTO erp_ops_audit_tamper (ID, ACTION_TYPE, EMP_ID, FIELD_NAME, OLD_VALUE, NEW_VALUE, CHANGE_BY, BYPASS, ATTEMPT_AT)
  VALUES (REPLACE(UUID(),'-',''), 'DELETE', OLD.EMP_ID, OLD.FIELD_NAME, OLD.OLD_VALUE, OLD.NEW_VALUE, USER(), IF(COALESCE(@audit_bypass,0)=1,'1','0'), NOW());
  IF COALESCE(@audit_bypass, 0) <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '审计日志禁止删除（BR-5.6-13）';
  END IF;
END$$
DELIMITER ;

-- ---- 5. 脱敏规则表 + 3 种子（D6：正则 + 掩码模板支持捕获组引用）----
CREATE TABLE IF NOT EXISTS erp_ops_mask_rule (
  ID            VARCHAR(32)  NOT NULL COMMENT '主键',
  CODE          VARCHAR(32)  NOT NULL COMMENT '规则编码',
  NAME          VARCHAR(64)  NOT NULL COMMENT '规则名称',
  PATTERN       VARCHAR(200) NOT NULL COMMENT 'Java 正则',
  MASK_TEMPLATE VARCHAR(100) NOT NULL COMMENT '掩码模板（$1/$2 捕获组引用）',
  STATUS        VARCHAR(1)   NOT NULL DEFAULT '1' COMMENT '1 启用 / 0 停用',
  SORT_ORDER    INT          NOT NULL DEFAULT 10 COMMENT '应用顺序（小者先；具体规则先于宽泛规则，如 ID_CARD 先于 BANK_CARD）',
  REMARK        VARCHAR(200) DEFAULT NULL,
  CREATE_BY     VARCHAR(64)  DEFAULT NULL,
  CREATE_DATE   DATETIME     DEFAULT NULL,
  UPDATE_BY     VARCHAR(64)  DEFAULT NULL,
  UPDATE_DATE   DATETIME     DEFAULT NULL,
  DEL_FLAG      VARCHAR(1)   NOT NULL DEFAULT '0',
  VER_NO        INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (ID),
  KEY IDX_MASK_RULE_CODE (CODE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计内容脱敏规则（16.7.3，内容模式脱敏替代列级；CODE 唯一性由应用层校验，普通索引避免逻辑删占位）';

-- SORT_ORDER 受控补列（重跑幂等；表已存在时也能补上）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='erp_ops_mask_rule' AND COLUMN_NAME='SORT_ORDER');
SET @s := IF(@c=0, 'ALTER TABLE erp_ops_mask_rule ADD COLUMN SORT_ORDER INT NOT NULL DEFAULT 10 COMMENT ''应用顺序（小者先）'' AFTER STATUS', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- CODE 唯一键 → 普通索引（受控幂等）：逻辑删行仍占唯一键，删过的规则码重建会撞键；
-- 唯一性改由应用层 save 校验（MaskRuleServiceImpl dup 检查带 DEL_FLAG='0'）
SET @c := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='erp_ops_mask_rule' AND INDEX_NAME='UK_MASK_RULE_CODE');
SET @s := IF(@c>0, 'ALTER TABLE erp_ops_mask_rule DROP INDEX UK_MASK_RULE_CODE, ADD INDEX IDX_MASK_RULE_CODE (CODE)', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 掩码模板用 Java 正则替换语法 $1/$2（捕获组引用）；SORT_ORDER 控制应用顺序
-- （具体规则先于宽泛规则：身份证 18 位会同时命中银行卡 16-19 位模式，须先被 ID_CARD 消费）
INSERT IGNORE INTO erp_ops_mask_rule (ID, CODE, NAME, PATTERN, MASK_TEMPLATE, STATUS, SORT_ORDER, REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
VALUES
  ('mask-id-card',  'ID_CARD',  '身份证号', '(\\d{6})\\d{8}(\\d{3}[\\dXx])', '$1********$2', '1', 10, '18 位身份证：保留前 6 后 4', 'migrate-191', NOW(), '0', 0),
  ('mask-mobile',   'MOBILE',   '手机号',   '(1[3-9]\\d)\\d{4}(\\d{4})',     '$1****$2',     '1', 20, '11 位手机号：保留前 3 后 4', 'migrate-191', NOW(), '0', 0),
  ('mask-bank-card','BANK_CARD','银行卡号', '(\\d{12,15})(\\d{4})',          '****$2',       '1', 30, '16-19 位银行卡：保留后 4（宽泛规则最后）', 'migrate-191', NOW(), '0', 0);

-- 已存在行补齐 SORT_ORDER（重跑幂等；历史行默认 10 会错序）
UPDATE erp_ops_mask_rule SET SORT_ORDER=10 WHERE CODE='ID_CARD';
UPDATE erp_ops_mask_rule SET SORT_ORDER=20 WHERE CODE='MOBILE';
UPDATE erp_ops_mask_rule SET SORT_ORDER=30 WHERE CODE='BANK_CARD';

-- ---- 6. 取证申请 + 取证包（D4/D5/D7）----
CREATE TABLE IF NOT EXISTS erp_ops_forensic_request (
  ID              VARCHAR(32)  NOT NULL COMMENT '主键',
  PURPOSE         VARCHAR(500) NOT NULL COMMENT '调阅目的（≥10 字）',
  RANGE_FROM      DATETIME     NOT NULL COMMENT '时间范围起',
  RANGE_TO        DATETIME     NOT NULL COMMENT '时间范围止',
  ACTION_FILTER   VARCHAR(100) DEFAULT NULL COMMENT '操作类型过滤（空=全部）',
  OBJECT_FILTER   VARCHAR(100) DEFAULT NULL COMMENT '对象范围过滤 EMP_ID 前缀（空=全部）',
  STATUS          VARCHAR(16)  NOT NULL DEFAULT 'APPLYING'
                  COMMENT 'APPLYING 待签 / READY 双签齐备 / PACKAGED 已生成包 / REJECTED 已驳回',
  APPLY_BY        VARCHAR(32)  NOT NULL COMMENT '申请人（不可为签署人，自签 403）',
  AUDIT_SIGN_BY   VARCHAR(32)  DEFAULT NULL COMMENT '内审签署人（ROLE_UAR_AUDITOR）',
  AUDIT_SIGN_AT   DATETIME     DEFAULT NULL,
  AUDIT_OPINION   VARCHAR(500) DEFAULT NULL,
  DPO_SIGN_BY     VARCHAR(32)  DEFAULT NULL COMMENT 'DPO 签署人（ROLE_DPO，须在内审签署之后）',
  DPO_SIGN_AT     DATETIME     DEFAULT NULL,
  DPO_OPINION     VARCHAR(500) DEFAULT NULL,
  REJECT_REASON   VARCHAR(500) DEFAULT NULL,
  CREATE_BY       VARCHAR(64)  DEFAULT NULL,
  CREATE_DATE     DATETIME     DEFAULT NULL,
  UPDATE_BY       VARCHAR(64)  DEFAULT NULL,
  UPDATE_DATE     DATETIME     DEFAULT NULL,
  DEL_FLAG        VARCHAR(1)   NOT NULL DEFAULT '0',
  VER_NO          INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (ID),
  KEY IDX_FORENSIC_REQ_STATUS (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计取证调阅申请（S-5.6-07，内审+DPO 双签）';

CREATE TABLE IF NOT EXISTS erp_ops_forensic_package (
  ID             VARCHAR(32)  NOT NULL COMMENT '主键',
  REQUEST_ID     VARCHAR(32)  NOT NULL COMMENT '所属申请',
  TOTAL_ROWS     INT          NOT NULL DEFAULT 0 COMMENT '总条数',
  SHARD_SIZE     INT          NOT NULL DEFAULT 10000 COMMENT '分片行数（EXPORT_ROW_LIMIT）',
  SHARD_COUNT    INT          NOT NULL DEFAULT 0 COMMENT '分片数',
  SEAL_JSON      TEXT         DEFAULT NULL COMMENT '封条 JSON（生成时间/人/条数/逐片 SHA-256，D4）',
  STATUS         VARCHAR(16)  NOT NULL DEFAULT 'GENERATED'
                 COMMENT 'GENERATED 可下载 / DESTROYED 已销毁',
  GENERATED_BY   VARCHAR(32)  DEFAULT NULL COMMENT '生成人',
  GENERATED_AT   DATETIME     DEFAULT NULL,
  EXPIRE_AT      DATETIME     NOT NULL COMMENT '过期时间 = 生成 +7d（S-5.6-07）',
  DESTROYED_AT   DATETIME     DEFAULT NULL,
  USED_BY        VARCHAR(32)  DEFAULT NULL COMMENT '使用完毕确认人',
  DOWNLOAD_COUNT INT          NOT NULL DEFAULT 0 COMMENT '下载次数（下载自身留痕）',
  CREATE_BY      VARCHAR(64)  DEFAULT NULL,
  CREATE_DATE    DATETIME     DEFAULT NULL,
  UPDATE_BY      VARCHAR(64)  DEFAULT NULL,
  UPDATE_DATE    DATETIME     DEFAULT NULL,
  DEL_FLAG       VARCHAR(1)   NOT NULL DEFAULT '0',
  VER_NO         INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (ID),
  UNIQUE KEY UK_FORENSIC_PKG_REQ (REQUEST_ID),
  KEY IDX_FORENSIC_PKG_EXPIRE (STATUS, EXPIRE_AT)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计取证包（BR-5.6-14 分片 SHA-256 封条，不落盘按需重查）';

-- ---- 7. DPO 角色 + 授予 admin（冒烟/测试兜底）----
INSERT IGNORE INTO erp_admin_role (ID, ROLE_CODE, ROLE_NAME, SORT_ORDER, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
VALUES ('role-dpo', 'ROLE_DPO', '数据保护官', 38, '1', 'migrate-191', NOW(), '0', 0);

INSERT IGNORE INTO erp_admin_user_role (ID, USER_ID, ROLE_ID)
VALUES ('ur-admin-dpo', 'user-admin', 'role-dpo');
