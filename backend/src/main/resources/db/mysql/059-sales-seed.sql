-- ============================================================================
-- 059 销售域种子与演示数据（add-sales-lead-to-cash tasks 16.1~16.6）
--     幂等策略：一律固定 ID + INSERT IGNORE；UPDATE 只按固定主键 SET（可重复执行）。
--     演示单号统一 SEED 前缀（SO-SEED-*），不与 nextNo 的日期前缀（SO2026…）相撞，
--     亦不被冒烟清理语句（LIKE 'SO2026%'）误删。
--     CREATE_BY='seed' 明示构造数据（design 风险表：账龄/及时率与真实数据同代码不同来源）。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 16.1 客户种子：渠道属性回填 + 税务资质缺失/特殊样本两级（集团 + 法人视图）
-- ----------------------------------------------------------------------------
UPDATE erp_mdm_customer_view SET CHANNEL = 'KEY_ACCOUNT'  WHERE ID = 'seed-view-9001';
UPDATE erp_mdm_customer_view SET CHANNEL = 'DIRECT'       WHERE ID = 'seed-view-9002';
UPDATE erp_mdm_customer_view SET CHANNEL = 'DISTRIBUTOR'  WHERE ID = 'seed-view-9003';
UPDATE erp_mdm_customer_view SET CHANNEL = 'ECOMMERCE'    WHERE ID = 'seed-view-9004';
UPDATE erp_mdm_customer_view SET CHANNEL = 'KEY_ACCOUNT'  WHERE ID = 'seed-view-9005';

-- 9006：税务资质缺失样本（税号未维护 → C-4.3-06 开票 L1 阻断）+ 营业执照过期样本（BR-4.3-08 报价阻断）
INSERT IGNORE INTO erp_mdm_customer_group
(ID, CUSTOMER_CODE, CUSTOMER_NAME, TAX_NO, USCC, LICENSE_EXPIRE, TAXPAYER_TYPE, TAX_QUAL_EXPIRE,
 TAX_CODE, CREDIT_RATING, CREDIT_LIMIT_TOTAL, STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('seed-cust-9006', 'CUST-9006', '泉州海丝贸易（税资缺失样本）', NULL, '91350000SEED9006',
 '2026-08-31', NULL, NULL, NULL, 'B', 100000.00, '1', 'seed', '0', 0);
INSERT IGNORE INTO erp_mdm_customer_view
(ID, GROUP_ID, SHIP_ADDRESS, CONTACT_NAME, CONTACT_PHONE, PAYMENT_TERMS, CHANNEL,
 CREDIT_LIMIT, STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('seed-view-9006', 'seed-cust-9006', '福建省泉州市丰泽区 Demo 路 6 号', '林海丝', '13800000006',
 'NET30', 'ECOMMERCE', 100000.00, '1', 'seed', '0', 0);

-- 9007：小规模纳税人（SMALL）+ 一般纳税人资格 30 天内到期（预警样本）
INSERT IGNORE INTO erp_mdm_customer_group
(ID, CUSTOMER_CODE, CUSTOMER_NAME, TAX_NO, USCC, LICENSE_EXPIRE, TAXPAYER_TYPE, TAX_QUAL_EXPIRE,
 TAX_CODE, CREDIT_RATING, CREDIT_LIMIT_TOTAL, STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('seed-cust-9007', 'CUST-9007', '宁波甬江商超（小规模样本）', '91330000SEED9007', '91330000SEED9007U',
 '2027-06-30', 'SMALL', DATE_ADD(CURDATE(), INTERVAL 25 DAY), NULL, 'C', 120000.00, '1', 'seed', '0', 0);
INSERT IGNORE INTO erp_mdm_customer_view
(ID, GROUP_ID, SHIP_ADDRESS, CONTACT_NAME, CONTACT_PHONE, PAYMENT_TERMS, CHANNEL,
 CREDIT_LIMIT, STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('seed-view-9007', 'seed-cust-9007', '浙江省宁波市鄞州区 Demo 大道 88 号', '王甬江', '13800000007',
 'COD', 'DIRECT', 120000.00, '1', 'seed', '0', 0);

-- ----------------------------------------------------------------------------
-- 16.2 价格协议种子：EXCLUSIVE×2 / LADDER×1 / TIME×2，覆盖三协议并存取价场景
--     9002×RM0001：LADDER（本地） + TIME（全局） + 冒烟自建 EXCLUSIVE → 三类并存
--     （取价优先级 EXCLUSIVE > LADDER > TIME，spec cross-domain-sharing）
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO erp_mdm_price_agreement
(ID, PA_CODE, PA_NAME, AGREEMENT_TYPE, CUSTOMER_GROUP_ID, CUSTOMER_VIEW_ID,
 EFFECTIVE_DATE, EXPIRE_DATE, STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('seed-pa-ex-9001', 'PA-SEED-EX-01', '华东实业专属价协议', 'EXCLUSIVE', 'seed-cust-9001', NULL,
 '2026-01-01', '2027-12-31', '1', 'seed', '0', 0),
('seed-pa-ex-9003', 'PA-SEED-EX-02', '粤港物流专属价协议', 'EXCLUSIVE', 'seed-cust-9003', NULL,
 '2026-01-01', '2027-12-31', '1', 'seed', '0', 0),
('seed-pa-ld-9002', 'PA-SEED-LD-01', '齐鲁建工量价阶梯协议', 'LADDER', 'seed-cust-9002', NULL,
 '2026-01-01', '2027-12-31', '1', 'seed', '0', 0),
('seed-pa-tm-001', 'PA-SEED-TM-01', '不锈钢管 Q4 时间价', 'TIME', NULL, NULL,
 '2026-10-01', '2026-12-31', '1', 'seed', '0', 0),
('seed-pa-tm-002', 'PA-SEED-TM-02', '精密配件全年时间价', 'TIME', NULL, NULL,
 '2026-01-01', '2027-06-30', '1', 'seed', '0', 0);

INSERT IGNORE INTO erp_mdm_price_agreement_line
(ID, PA_ID, ITEM_CODE, UNIT_PRICE, MIN_QTY, MAX_QTY, CREATE_BY, DEL_FLAG, VER_NO) VALUES
-- EXCLUSIVE：一口价（9001×RM1 97 低于阶梯与时间价，用于验证专属优先）
('seed-pal-ex-01', 'seed-pa-ex-9001', 'RM0001000001', 97.0000, NULL, NULL, 'seed', '0', 0),
('seed-pal-ex-02', 'seed-pa-ex-9003', 'RM0002000001', 55.0000, NULL, NULL, 'seed', '0', 0),
-- LADDER：达成量阶梯三段
('seed-pal-ld-01', 'seed-pa-ld-9002', 'RM0001000001', 100.0000, 1, 99, 'seed', '0', 0),
('seed-pal-ld-02', 'seed-pa-ld-9002', 'RM0001000001', 96.0000, 100, 499, 'seed', '0', 0),
('seed-pal-ld-03', 'seed-pa-ld-9002', 'RM0001000001', 92.0000, 500, 99999, 'seed', '0', 0),
-- TIME：全局时间价
('seed-pal-tm-01', 'seed-pa-tm-001', 'RM0001000001', 99.0000, NULL, NULL, 'seed', '0', 0),
('seed-pal-tm-02', 'seed-pa-tm-002', 'RM0002000001', 50.0000, NULL, NULL, 'seed', '0', 0);

-- ----------------------------------------------------------------------------
-- 16.4 库存种子：补 WH-02 成品仓批次（多批次/多仓 ATP 演示；既有 WH-MAIN 多批次、
--     QC 待检（QC_QTY>0 多处）与寄售（erp_inv_vmi_stock）已覆盖排除逻辑）
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO erp_inv_stock
(ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, QTY, QC_QTY, AVAILABLE_QTY,
 CREATE_BY, DEL_FLAG, VER_NO) VALUES
('seed-stock-wh02-rm1', 'WH-02', 'RM0001000001', '不锈钢管 DN50', 'WH02-2610-01',
 300.0000, 10.0000, 290.0000, 'seed', '0', 0);

-- ----------------------------------------------------------------------------
-- 16.5 演示数据：线索 / 商机（含丢失） / 报价 / 订单（含冻结与草稿） /
--      发货（已确认） / 退货（退款完成） 全链分支
-- ----------------------------------------------------------------------------
-- 线索三条：待跟进 / 跟进中 / 已转化
INSERT IGNORE INTO erp_crm_lead
(ID, LEAD_NO, SOURCE_CHANNEL, COMPANY_NAME, CONTACT_NAME, CONTACT_PHONE, DEMAND_DESC,
 EXPECT_AMOUNT, EXPECT_CLOSE_DATE, SCORE, GRADE, STATUS, CONVERTED_OPP_ID,
 LAST_FOLLOWUP_AT, OWNER_NAME, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('seed-lead-01', 'LD-SEED-0001', 'WEBSITE', '漳州云霄食品有限公司', '陈云霄', '13900000001',
 '线上询价不锈钢管 200 支，关注交期', 24000.00, '2026-11-30', 78, 'B', 'OPEN', NULL,
 NULL, '销售员A', 'seed', '0', 0),
('seed-lead-02', 'LD-SEED-0002', 'EXHIBITION', '佛山禅城机电设备厂', '赵禅城', '13900000002',
 '展会名片转化，设备配套管件年采意向', 56000.00, '2026-12-31', 86, 'A', 'OPEN', NULL,
 '2026-10-05 10:30:00', '销售员A', 'seed', '0', 0),
('seed-lead-03', 'LD-SEED-0003', 'REFERRAL', '青岛黄海冷链物流', '孙黄海', '13900000003',
 '老客户介绍，冷链管线改造需求', 120000.00, '2026-10-31', 91, 'A', 'CONVERTED',
 'seed-opp-01', '2026-09-28 15:00:00', '销售员A', 'seed', '0', 0);

-- 商机三条：报价阶段（可转化） / 已丢失（LOSS 分支） / 合同签订（WON）
INSERT IGNORE INTO erp_crm_opportunity
(ID, OPP_NO, OPP_NAME, CUSTOMER_ID, CUSTOMER_CODE, CUSTOMER_NAME, LEAD_ID,
 EXPECT_AMOUNT, EXPECT_CLOSE_DATE, COMPETITION, DEMAND_SUMMARY, STAGE, STAGE_ENTERED_AT,
 STAGE_PROBABILITY, NEXT_ACTION, NEXT_ACTION_DATE, STATUS, LOSS_CATEGORY, LOSS_REMARK, LOSS_AT,
 CLOSE_AT, OWNER_NAME, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('seed-opp-01', 'OPP-SEED-0001', '黄海冷链管线改造商机', 'seed-cust-9004', 'CUST-9004',
 '渤海粮油优质客户', 'seed-lead-03', 120000.00, '2026-11-15', '本地两家竞品报价低 5%',
 '冷链管线改造一期，DN50 不锈钢管 1200 支', 'QUOTE', '2026-10-01 09:00:00',
 60, '报价提交审批', '2026-10-10', 'OPEN', NULL, NULL, NULL, NULL, '销售员A', 'seed', '0', 0),
('seed-opp-02', 'OPP-SEED-0002', '禅城机电年采商机（丢失）', 'seed-cust-9004', 'CUST-9004',
 '渤海粮油优质客户', NULL, 56000.00, '2026-10-15', '对手报价低 12%',
 '年采框架谈判未达心理价位', 'NEGOTIATION', '2026-09-10 09:00:00',
 40, NULL, NULL, 'LOST', 'PRICE', '对手整体报价低 12%，客户选择本地供应商', '2026-09-30 17:00:00',
 '2026-09-30 17:00:00', '销售员A', 'seed', '0', 0),
('seed-opp-03', 'OPP-SEED-0003', '粤港物流长期供货商机', 'seed-cust-9003', 'CUST-9003',
 '粤港物流长期户', NULL, 60000.00, '2026-10-31', '无',
 '长期供货协议已谈妥，进入合同签订', 'CONTRACT', '2026-10-02 09:00:00',
 100, '合同签订', '2026-10-08', 'WON', NULL, NULL, NULL, '2026-10-06 16:00:00', '销售员A',
 'seed', '0', 0);

-- 报价两条：已发布（待转化） / 已转化（回链演示 SO）
INSERT IGNORE INTO erp_sd_quote
(ID, DRAFT_NO, QUOTE_NO, OPP_ID, OPP_NO, CUSTOMER_ID, CUSTOMER_CODE, CUSTOMER_NAME,
 ORDER_TYPE, CONTACT_NAME, TOTAL_AMOUNT, TOTAL_COST, MARGIN_RATE, VALID_FROM, VALID_TO,
 STATUS, SO_ID, SO_NO, CONVERTED_AT, PUBLISH_BY, PUBLISH_AT, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('seed-qt-01', 'DQ-SEED-0001', NULL, 'seed-opp-01', 'OPP-SEED-0001', 'seed-cust-9004',
 'CUST-9004', '渤海粮油优质客户', 'STANDARD', '李采购', 15000.00, 9750.00, 0.3500,
 '2026-10-01', '2026-12-31', 'PUBLISHED', NULL, NULL, NULL, '销售员A', '2026-10-04 11:00:00',
 'seed', '0', 0),
('seed-qt-02', 'DQ-SEED-0002', 'QT-SEED-0002', 'seed-opp-03', 'OPP-SEED-0003', 'seed-cust-9003',
 'CUST-9003', '粤港物流长期户', 'STANDARD', '周长期', 20000.00, 9000.00, 0.5500,
 '2026-10-01', '2026-12-31', 'CONVERTED', 'seed-so-01', 'SO-SEED-0001', '2026-10-06 15:30:00',
 '销售员A', '2026-10-03 10:00:00', 'seed', '0', 0);
INSERT IGNORE INTO erp_sd_quote_line
(ID, QUOTE_ID, LINE_NO, ITEM_CODE, ITEM_NAME, QTY, BASE_UNIT, UNIT_PRICE, AMOUNT,
 PRICE_SOURCE, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('seed-qt-01-l1', 'seed-qt-01', 1, 'RM0001000001', '不锈钢管 DN50', 150, 'PCS', 100.0000,
 15000.00, 'LIST', 'seed', '0', 0),
('seed-qt-02-l1', 'seed-qt-02', 1, 'RM0001000001', '不锈钢管 DN50', 200, 'PCS', 100.0000,
 20000.00, 'LIST', 'seed', '0', 0);

-- 订单三条：已确认已发货（主链） / 信用冻结（挂起分支） / 草稿
INSERT IGNORE INTO erp_sd_so
(ID, SO_NO, CUSTOMER_ID, CUSTOMER_CODE, CUSTOMER_NAME, ORDER_TYPE, PAYMENT_TERMS,
 TRADE_TERMS, TOTAL_AMOUNT, MARGIN_RATE, STATUS, SOURCE_TYPE, SOURCE_ID, OPP_ID,
 REMARK, EVENT_VERSION, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('seed-so-01', 'SO-SEED-0001', 'seed-cust-9003', 'CUST-9003', '粤港物流长期户',
 'STANDARD', 'NET30', 'FOB', 20000.00, 0.5500, 'CONFIRMED', 'QUOTE', 'seed-qt-02',
 'seed-opp-03', '演示：报价转化 → 确认 → 发货 → 退货主链', 1, 'seed', '0', 0),
('seed-so-02', 'SO-SEED-0002', 'seed-cust-9005', 'CUST-9005', '申城研究院（资格过期样本）',
 'STANDARD', 'NET30', 'FOB', 50000.00, 0.3000, 'CREDIT_FREEZE', 'MANUAL', NULL, NULL,
 '演示：建单即检信用冻结分支（3.3.2）', 0, 'seed', '0', 0),
('seed-so-03', 'SO-SEED-0003', 'seed-cust-9007', 'CUST-9007', '宁波甬江商超（小规模样本）',
 'STANDARD', 'NET30', 'FOB', 30000.00, 0.4000, 'DRAFT', 'MANUAL', NULL, NULL,
 '演示：草稿订单（未提交；挂 9007 避免与发货冒烟争 9004 额度）', 0, 'seed', '0', 0);
INSERT IGNORE INTO erp_sd_so_line
(ID, SO_ID, LINE_NO, ITEM_CODE, ITEM_NAME, QTY, BASE_UNIT, UNIT_PRICE, AMOUNT,
 PRICE_SOURCE, LINE_STATUS, SHIPPED_QTY, INVOICED_QTY, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('seed-so-01-l1', 'seed-so-01', 1, 'RM0001000001', '不锈钢管 DN50', 200, 'PCS', 100.0000,
 20000.00, 'LIST', 'SHIPPED', 200, 0, 'seed', '0', 0),
('seed-so-02-l1', 'seed-so-02', 1, 'RM0001000001', '不锈钢管 DN50', 500, 'PCS', 100.0000,
 50000.00, 'LIST', 'DRAFT', 0, 0, 'seed', '0', 0),
('seed-so-03-l1', 'seed-so-03', 1, 'RM0001000001', '不锈钢管 DN50', 300, 'PCS', 100.0000,
 30000.00, 'LIST', 'DRAFT', 0, 0, 'seed', '0', 0);

-- 信用冻结演示（建单即检分支：PREV=DRAFT，缺口 = 180000 + 50000 - 200000 = 30000）
INSERT IGNORE INTO erp_sd_credit_freeze
(ID, SO_ID, SO_NO, CUSTOMER_ID, CUSTOMER_NAME, CREDIT_CHECK_ID, GAP_AMOUNT, STATUS,
 PREV_STATUS, FREEZE_REASON, FROZEN_AT, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('seed-freeze-01', 'seed-so-02', 'SO-SEED-0002', 'seed-cust-9005',
 '申城研究院（资格过期样本）', NULL, 30000.00, 'FROZEN', 'DRAFT',
 '演示：余额 180000 + 本单 50000 > 额度 200000', '2026-10-06 09:30:00', 'seed', '0', 0);
INSERT IGNORE INTO erp_sd_prepayment_notice
(ID, NOTICE_NO, FREEZE_ID, SO_ID, SO_NO, CUSTOMER_ID, CUSTOMER_NAME, GAP_AMOUNT,
 STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('seed-notice-01', 'PN-SEED-0001', 'seed-freeze-01', 'seed-so-02', 'SO-SEED-0002',
 'seed-cust-9005', '申城研究院（资格过期样本）', 30000.00, 'PENDING', 'seed', '0', 0);

-- 发货一条：已确认（含物流与签收时限），行回链 SO
INSERT IGNORE INTO erp_sd_shipment
(ID, SHIP_NO, SHIP_TYPE, CUSTOMER_ID, CUSTOMER_CODE, CUSTOMER_NAME, WAREHOUSE_CODE,
 STATUS, TOTAL_QTY, TOTAL_AMT, LOGISTICS_CO, LOGISTICS_NO, SHIP_AT, SIGN_DUE_DATE,
 POST_BY, POST_AT, CONFIRM_BY, CONFIRM_AT, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('seed-ship-01', 'SH-SEED-0001', 'NORMAL', 'seed-cust-9003', 'CUST-9003', '粤港物流长期户',
 'WH-MAIN', 'CONFIRMED', 200, 20000.00, '顺丰速运', 'SF-SEED-0001', '2026-10-05 14:00:00',
 '2026-10-12', 'tp_wh', '2026-10-05 10:00:00', 'tp_wh', '2026-10-05 14:00:00', 'seed', '0', 0);
INSERT IGNORE INTO erp_sd_shipment_line
(ID, SHIP_ID, LINE_NO, SO_ID, SO_LINE_ID, ITEM_CODE, ITEM_NAME, QTY, BASE_UNIT,
 WAREHOUSE_CODE, LINE_STATUS, POST_AT, BATCH_ALLOC, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('seed-ship-01-l1', 'seed-ship-01', 1, 'seed-so-01', 'seed-so-01-l1', 'RM0001000001',
 '不锈钢管 DN50', 200, 'PCS', 'WH-MAIN', 'POSTED', '2026-10-05 10:00:00',
 '[{"batchNo":"B20261005","qty":200}]', 'seed', '0', 0);

-- 退货一条：退款完成（DONE）分支，关联演示 SO/发货
INSERT IGNORE INTO erp_sd_return
(ID, RETURN_NO, SOURCE_TYPE, SHIP_ID, SHIP_NO, SO_ID, SO_NO, CUSTOMER_ID, CUSTOMER_CODE,
 CUSTOMER_NAME, TOTAL_QTY, TOTAL_AMT, HANDLE_TYPE, RETURN_REASON, REASON_TYPE,
 INVOICE_FLAG, STATUS, APPLY_BY, APPLY_AT, JUDGE_BY, JUDGE_AT, JUDGE_NOTE, LIABILITY,
 OVERDUE, JUDGE_HANDLE, SUBMIT_AT, REFUND_AMT, REFUND_AT, REFUND_BY, REFUND_PAY_NO,
 STOCK_IN, STOCK_IN_AT, STOCK_IN_BY, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('seed-ret-01', 'RT-SEED-0001', 'MANUAL', 'seed-ship-01', 'SH-SEED-0001', 'seed-so-01',
 'SO-SEED-0001', 'seed-cust-9003', 'CUST-9003', '粤港物流长期户', 20, 2000.00, 'REFUND',
 '演示：到货 7 日内管材划伤', 'QUALITY', '0', 'DONE', '销售员A', '2026-10-06 09:00:00',
 '销售经理B', '2026-10-06 14:00:00', '到货查验为我方质检疏漏，核定全退', 'OUR_QUALITY',
 '0', 'REFUND', '2026-10-06 15:00:00', 2000.00, '2026-10-07 10:00:00', 'finmgr',
 'PAY-SEED-0001', '1', '2026-10-07 09:00:00', 'tp_wh', 'seed', '0', 0);
INSERT IGNORE INTO erp_sd_return_line
(ID, RETURN_ID, LINE_NO, SO_LINE_ID, SO_LINE_NO, SHIP_LINE_ID, ITEM_CODE, ITEM_NAME,
 QTY, JUDGE_QTY, IN_QTY, STOCK_BATCH_NO, QC_FLAG, BASE_UNIT, UNIT_PRICE, AMOUNT,
 WAREHOUSE_CODE, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('seed-ret-01-l1', 'seed-ret-01', 1, 'seed-so-01-l1', 1, 'seed-ship-01-l1',
 'RM0001000001', '不锈钢管 DN50', 20, 20, 20, 'B20261005', '0', 'PCS', 100.0000,
 2000.00, 'WH-MAIN', 'seed', '0', 0);

-- ----------------------------------------------------------------------------
-- 16.6 返利三配置 + 框架协议（含临界余量）
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO erp_sd_rebate_target
(ID, CUSTOMER_ID, CUSTOMER_CODE, CUSTOMER_NAME, QUARTER, TARGET_AMT, REMARK,
 CREATE_BY, DEL_FLAG, VER_NO) VALUES
('seed-rbt-9001-q4', 'seed-cust-9001', 'CUST-9001', '华东实业标杆户', '2026Q4',
 500000.00, '演示：Q4 季度销售目标', 'seed', '0', 0),
('seed-rbt-9002-q4', 'seed-cust-9002', 'CUST-9002', '齐鲁建工大客户', '2026Q4',
 400000.00, '演示：Q4 季度销售目标', 'seed', '0', 0);

-- 全局默认阶梯（CUSTOMER_ID 空 = 全局兜底）+ 9001 客户级阶梯（客户优先）
INSERT IGNORE INTO erp_sd_rebate_policy
(ID, POLICY_NO, POLICY_NAME, CUSTOMER_ID, BAND_FROM, BAND_TO, REBATE_RATE,
 VALID_FROM, VALID_TO, STATUS, SEQ, REMARK, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('seed-rbp-g-1', 'RBP-SEED-G-01', '全局阶梯-基础段', NULL, 0, 100, 0.50,
 '2026-01-01', '2026-12-31', 'EFFECTIVE', 1, '达成率 0~100%', 'seed', '0', 0),
('seed-rbp-g-2', 'RBP-SEED-G-01', '全局阶梯-超额段', NULL, 100, 120, 1.00,
 '2026-01-01', '2026-12-31', 'EFFECTIVE', 2, '达成率 100~120%', 'seed', '0', 0),
('seed-rbp-g-3', 'RBP-SEED-G-01', '全局阶梯-冲刺段（无上限）', NULL, 120, NULL, 1.50,
 '2026-01-01', '2026-12-31', 'EFFECTIVE', 3, '达成率 120% 以上（BAND_TO 空=无上限）', 'seed', '0', 0),
('seed-rbp-c1-1', 'RBP-SEED-C-01', '华东实业阶梯-基础段', 'seed-cust-9001', 0, 100, 0.80,
 '2026-01-01', '2026-12-31', 'EFFECTIVE', 1, '客户级优先于全局', 'seed', '0', 0),
('seed-rbp-c1-2', 'RBP-SEED-C-01', '华东实业阶梯-超额段', 'seed-cust-9001', 100, NULL, 1.60,
 '2026-01-01', '2026-12-31', 'EFFECTIVE', 2, '达成率 100% 以上无上限', 'seed', '0', 0);

INSERT IGNORE INTO erp_sd_rebate_budget
(ID, BUDGET_YEAR, TOTAL_AMT, Q1_AMT, Q2_AMT, Q3_AMT, Q4_AMT, REMARK,
 CREATE_BY, DEL_FLAG, VER_NO) VALUES
('seed-rbg-2026', 2026, 1200000.00, 300000.00, 300000.00, 300000.00, 300000.00,
 '演示：2026 年度返利预算（缺省按季 25% 分解）', 'seed', '0', 0);

-- 框架协议：行1 临界余量（总量 1000 − 已下达 950 = 可下达仅 50）、行2 未下达
INSERT IGNORE INTO erp_sd_framework
(ID, FW_NO, CUSTOMER_ID, CUSTOMER_CODE, CUSTOMER_NAME, TITLE, EFFECTIVE_DATE, EXPIRE_DATE,
 TOTAL_QTY, STATUS, CHANGE_LOG, REMARK, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('seed-fw-01', 'FW-SEED-0001', 'seed-cust-9002', 'CUST-9002', '齐鲁建工大客户',
 '2026 年度不锈钢管框架协议', '2026-01-01', '2026-12-31', 1500.0000, 'EFFECTIVE',
 '[]', '演示：行1 临界余量（C-4.3-10 阻断演示）', 'seed', '0', 0);
INSERT IGNORE INTO erp_sd_framework_line
(ID, FRAMEWORK_ID, LINE_NO, ITEM_CODE, ITEM_NAME, BASE_UNIT, TOTAL_QTY, RELEASED_QTY,
 SHIPPED_QTY, UNIT_PRICE, WAREHOUSE_CODE, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('seed-fw-01-l1', 'seed-fw-01', 1, 'RM0001000001', '不锈钢管 DN50', 'PCS', 1000.0000,
 950.0000, 950.0000, 96.0000, 'WH-MAIN', 'seed', '0', 0),
('seed-fw-01-l2', 'seed-fw-01', 2, 'RM0002000001', '精密结构件', 'PCS', 500.0000,
 0.0000, 0.0000, 50.0000, 'WH-MAIN', 'seed', '0', 0);
INSERT IGNORE INTO erp_sd_framework_release
(ID, RELEASE_NO, FRAMEWORK_ID, FW_LINE_ID, LINE_NO, CUSTOMER_ID, CUSTOMER_CODE,
 CUSTOMER_NAME, ITEM_CODE, ITEM_NAME, QTY, SHIPPED_QTY, DELIVER_DATE, STATUS,
 LAST_SHIP_NO, REMARK, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('seed-fwrl-01', 'FWRL-SEED-0001', 'seed-fw-01', 'seed-fw-01-l1', 1, 'seed-cust-9002',
 'CUST-9002', '齐鲁建工大客户', 'RM0001000001', '不锈钢管 DN50', 950.0000, 950.0000,
 '2026-09-30', 'SHIPPED', 'SH-SEED-0002', '演示：已发完下达单', 'seed', '0', 0);
