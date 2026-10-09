-- 021 信用额度（客户管理 1.3.2，FR-4.1-6-3 / BR-4.1-33 / C-4.3-13）
-- MySQL 无 ADD COLUMN IF NOT EXISTS（MariaDB 语法），靠 marker 保证单次执行；
-- 若执行中断须手动补跑（marker 已记录不会重跑，见 013 教训）

ALTER TABLE erp_mdm_customer_view
    ADD COLUMN TEMP_CREDIT_LIMIT DECIMAL(18,2) NULL COMMENT '临时额度（BR-4.1-33，到期自动回滚）' AFTER CREDIT_LIMIT,
    ADD COLUMN TEMP_EXPIRE_DATE DATE NULL COMMENT '临时额度有效期（设临时额度时必填 > 当天）',
    ADD COLUMN LAST_REVIEW_DATE DATE NULL COMMENT '年度复审日期（C-4.3-13 台账）',
    ADD COLUMN COMPRESSED_LIMIT DECIMAL(18,2) NULL COMMENT '复审超期压缩后的生效额度（非空=压缩中，恢复即清空；原值不动）';
