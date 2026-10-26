-- ============================================================
-- 129-qms-sampling-record.sql
-- change: rework-sampling-inspection（6.3 抽样检验，spec inspection-lot
--         ADDED 抽样执行记录 + inspection-record-archive 新 capability）
-- 顺序：1) 抽样执行记录表（批 × 多条留痕）  2) 检验批补列 SAMPLED_FLAG
-- 幂等：全部 IF NOT EXISTS / ADD COLUMN IF NOT EXISTS（可连跑多遍）
-- ============================================================

-- ---------- 1) 抽样执行记录（design D1，spec 抽样执行记录） ----------
--     每批 0..n 条：INITIAL 首次 / ESCALATE 加大抽样 / RESAMPLE 补抽，
--     多条并存不覆盖（FR-4.12-2-1 要点③ 异常② 留痕）。
--     抽样为可选留痕（探索 D2）：缺失不阻断录入/判定/放行。
CREATE TABLE IF NOT EXISTS erp_qms_sampling_record (
    ID VARCHAR(64) NOT NULL,
    -- 批内序号 1..n（同批多条按 SEQ 升序展示）
    SEQ INT NOT NULL DEFAULT 1 COMMENT '批内序号',
    LOT_ID VARCHAR(64) NOT NULL COMMENT '检验批 ID',
    -- 实际抽样数量，可大于理论样本量（加大抽样）
    ACTUAL_SAMPLE_QTY DECIMAL(18,4) NOT NULL COMMENT '实际抽样数量',
    -- RANDOM 随机 / NINE_POINT 九点取样 / FULL 逐件 / OTHER 其他（备注说明）
    SAMPLE_METHOD VARCHAR(16) NOT NULL COMMENT '抽样方法',
    SAMPLER_ID VARCHAR(64) DEFAULT NULL COMMENT '抽样人 ID',
    SAMPLER_NAME VARCHAR(64) DEFAULT NULL COMMENT '抽样人姓名',
    SAMPLED_TIME DATETIME NOT NULL COMMENT '抽样时间',
    -- INITIAL / ESCALATE / RESAMPLE
    TRIGGER_REASON VARCHAR(16) NOT NULL DEFAULT 'INITIAL' COMMENT '触发原因',
    REMARK VARCHAR(500) DEFAULT NULL COMMENT '备注（异常描述/其他方法说明）',
    -- BaseEntity 套件
    CREATE_BY VARCHAR(64) DEFAULT NULL,
    CREATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY VARCHAR(64) DEFAULT NULL,
    UPDATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    DEL_FLAG CHAR(1) DEFAULT '0',
    VER_NO INT DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_QMS_SAMP_REC_LOT (LOT_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='检验批抽样执行记录（6.3.1）';

-- ---------- 2) 检验批补列 SAMPLED_FLAG（design D1 冗余标记） ----------
--     '1' = 已有至少一条抽样记录；写入点唯一（SamplingRecordService
--     插入后同事务更新）；board?samplingDone=false 过滤用。
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_qms_inspection_lot'
               AND COLUMN_NAME = 'SAMPLED_FLAG');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_qms_inspection_lot ADD COLUMN SAMPLED_FLAG VARCHAR(1) DEFAULT NULL COMMENT ''1=已完成抽样'' AFTER OVERDUE_FLAG',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 回填（当前库无历史抽样记录，恒空；保留以备后续从 record 反推）
UPDATE erp_qms_inspection_lot l
   SET l.SAMPLED_FLAG = '1'
 WHERE l.SAMPLED_FLAG IS NULL
   AND EXISTS (SELECT 1 FROM erp_qms_sampling_record r WHERE r.LOT_ID = l.ID AND r.DEL_FLAG = '0');
