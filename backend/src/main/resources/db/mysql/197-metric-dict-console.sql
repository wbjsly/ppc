-- ============================================================
-- 197-metric-dict-console.sql
-- change: add-metric-dict-console（14.3 报表分析-指标口径，
--         spec bi-metric-dictionary MODIFIED + bi-metric-lineage ADDED，
--         BR-4.10-04 / C-4.10-08 / S-4.10-15）
--   两表（design D1）：
--     erp_bi_metric_dict    幂等加 5 列（十二要素：公式类型/分子/分母/维度/责任人）
--     erp_bi_metric_lineage 血缘四层关系快照（TABLE/METRIC/REPORT，只增审计）
--   菜单 14.3 三行权限幂等 UPDATE（ROLE_BI_DESIGNER,ROLE_ADMIN）
-- ============================================================
-- 幂等：条件 ALTER / CREATE TABLE IF NOT EXISTS / 受控 UPDATE，可重跑
-- ============================================================

-- ---- 十二要素扩展（design D3：全可空兼容存量，行级版本机制不变）----
SET @c1 := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='erp_bi_metric_dict' AND COLUMN_NAME='FORMULA_TYPE');
SET @s1 := IF(@c1=0, 'ALTER TABLE erp_bi_metric_dict
    ADD COLUMN FORMULA_TYPE VARCHAR(16) NOT NULL DEFAULT ''RATIO'' COMMENT ''公式类型：RATIO 比率/PRODUCT 连乘/ACCUM 累加'' AFTER SRC_MAPPING,
    ADD COLUMN NUMERATOR VARCHAR(500) DEFAULT NULL COMMENT ''分子（计算公式及过滤条件）'' AFTER FORMULA_TYPE,
    ADD COLUMN DENOMINATOR VARCHAR(500) DEFAULT NULL COMMENT ''分母（比率型必填；连乘/累加填 —）'' AFTER NUMERATOR,
    ADD COLUMN DIMENSIONS VARCHAR(500) DEFAULT NULL COMMENT ''下钻维度（组织/客户/物料/期间等）'' AFTER DENOMINATOR,
    ADD COLUMN OWNER VARCHAR(64) DEFAULT NULL COMMENT ''业务口径责任人'' AFTER DIMENSIONS', 'SELECT 1');
PREPARE st1 FROM @s1; EXECUTE st1; DEALLOCATE PREPARE st1;

-- ---- 血缘四层关系快照（design D4，spec bi-metric-lineage）----
CREATE TABLE IF NOT EXISTS erp_bi_metric_lineage (
  ID               VARCHAR(32)  NOT NULL COMMENT '主键',
  METRIC_KEY       VARCHAR(64)  NOT NULL COMMENT '指标编码',
  LINEAGE_VERSION  INT          NOT NULL DEFAULT 1 COMMENT '口径版本号（快照与版本绑定，历史可回溯）',
  NODE_TYPE        VARCHAR(16)  NOT NULL COMMENT '关联方类型：TABLE 上游表 / METRIC 衍生指标 / REPORT 引用报表',
  NODE_ID          VARCHAR(64)  NOT NULL COMMENT '关联方标识（表名 / 指标编码 / 模板 ID）',
  NODE_NAME        VARCHAR(128) DEFAULT NULL COMMENT '关联方名称（快照冗余）',
  HIGH_RISK        VARCHAR(1)   NOT NULL DEFAULT '0' COMMENT '高风险标注（对外披露/驾驶舱核心报表）',
  SNAPSHOT_AT      DATETIME     DEFAULT NULL COMMENT '快照生成时间',
  REMARK           VARCHAR(500) DEFAULT NULL,
  CREATE_BY        VARCHAR(64)  DEFAULT NULL,
  CREATE_DATE      DATETIME     DEFAULT NULL,
  UPDATE_BY        VARCHAR(64)  DEFAULT NULL,
  UPDATE_DATE      DATETIME     DEFAULT NULL,
  DEL_FLAG         VARCHAR(1)   NOT NULL DEFAULT '0',
  VER_NO           INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (ID),
  UNIQUE KEY UK_BI_LINEAGE (METRIC_KEY, LINEAGE_VERSION, NODE_TYPE, NODE_ID),
  KEY IDX_BI_LINEAGE_KEY (METRIC_KEY, LINEAGE_VERSION)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标血缘四层关系快照（14.3.2 变更影响，spec bi-metric-lineage）';

-- ---- 菜单 14.3 三行权限幂等 UPDATE（BI_DESIGNER 写 + ADMIN）----
UPDATE erp_admin_menu SET PERM = 'ROLE_BI_DESIGNER,ROLE_ADMIN'
WHERE MENU_CODE IN ('14.3.1', '14.3.2', '14.3.3') AND (PERM IS NULL OR PERM = '');
