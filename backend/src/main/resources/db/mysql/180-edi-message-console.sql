-- ============================================================
-- 180-edi-message-console.sql
-- change: add-edi-message-console（M12.4 报文处理操作台）
--   ① erp_intf_msg_retry_trace 重试轨迹表（design D1：
--     语法失败分支 syntaxResult 每次重发被覆盖只剩最后一次，
--     BR-4.9-24 工单要求"附错误位置清单"→ 逐次留痕不覆盖，只 INSERT）
--   ② erp_intf_msg_receipt 回执留存表（design D1：
--     回执现仅 receiptSent 标记、内容不落库，FR-4.9-4-5 查询无数据源 →
--     回执全文快照留存；SENT_FLAG 预留列 design D4 恒 0，语义待异步发送器立项）
-- ============================================================
-- 幂等：CREATE TABLE IF NOT EXISTS（索引随建表内联，沿 179 惯例）
-- ============================================================

-- ---------- 1. 重试轨迹表（只 INSERT，服务层不提供 UPDATE/DELETE） ----------

CREATE TABLE IF NOT EXISTS erp_intf_msg_retry_trace (
    ID             VARCHAR(64)  NOT NULL,
    MSG_ID         VARCHAR(64)  NOT NULL COMMENT '报文台账 ID（erp_intf_message.ID）',
    ATTEMPT_NO     INT          NOT NULL COMMENT '尝试序号（第 N 次，自 1 起，= 该次失败后的 retryCount）',
    FAIL_AT        DATETIME     NOT NULL COMMENT '本次语法校验失败时间',
    ERRORS_JSON    LONGTEXT     DEFAULT NULL COMMENT '错误位置清单快照（行号+段号+字段号数组，不随下次重发覆盖）',
    CREATE_BY      VARCHAR(64)  DEFAULT NULL,
    CREATE_DATE    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY      VARCHAR(64)  DEFAULT NULL,
    UPDATE_DATE    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG       VARCHAR(1)   NOT NULL DEFAULT '0',
    VER_NO         INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_TRACE_MSG (MSG_ID, ATTEMPT_NO)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='EDI 语法层重试轨迹（逐次留痕不可改删，spec edi-message-processing 留痕要求）';

-- ---------- 2. 处理回执留存表（只 INSERT，每次处理结论各留一条） ----------

CREATE TABLE IF NOT EXISTS erp_intf_msg_receipt (
    ID             VARCHAR(64)  NOT NULL,
    MSG_ID         VARCHAR(64)  NOT NULL COMMENT '报文台账 ID',
    MSG_NO         VARCHAR(64)  NOT NULL COMMENT '报文编号（冗余便于按号查询）',
    CONCLUSION     VARCHAR(32)  NOT NULL COMMENT '处理结论 PROCESSED/PARTIAL/FAILED/SKIPPED 等',
    RECEIPT_JSON   LONGTEXT     DEFAULT NULL COMMENT '回执全文快照（报文编号/状态/错误详情/处理时间/行级结果，与台账同源组装）',
    RECEIPT_AT     DATETIME     NOT NULL COMMENT '回执生成时间',
    SENT_FLAG      VARCHAR(1)   NOT NULL DEFAULT '0' COMMENT '送达标记（预留：异步回执发送器立项前恒 0，design D4）',
    CREATE_BY      VARCHAR(64)  DEFAULT NULL,
    CREATE_DATE    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY      VARCHAR(64)  DEFAULT NULL,
    UPDATE_DATE    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG       VARCHAR(1)   NOT NULL DEFAULT '0',
    VER_NO         INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_RECEIPT_MSG (MSG_ID, RECEIPT_AT),
    KEY IDX_RECEIPT_NO (MSG_NO)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='EDI 处理回执留存（含重复跳过回执，spec edi-message-processing 回执留存要求）';
