# Design

## Context

- 既有衔接点：PR `PENDING_RFQ`（2.1 流转终点）、紧急 `EmergencyService.clearance(prNo)`（允许 1 家的放行判定源）、`MdmTaxCodeDao.selectHit`（按日期取税率算含税）、`MdmSupplierDao` 五态（QUALIFIED）、`RequisitionStateMachine.plusBusinessDays/businessDaysBetween`（跳周末）、SecurityConfig `proc/**` ADMIN、`OutboxPublisher`
- 规格：FR-4.2-2-1/2、C-4.2-01（MIN_QUOTE_COUNT=3）、BR-4.2-12（±20%）、BR-4.2-13（不足处置）、BR-4.2-14（双轨）、参数表 L266 `MIN_QUOTE_COUNT`
- 菜单 M2-2-1 已注册（099 行 48），路由未挂；询价域零代码，迁移下一号 030

## Goals / Non-Goals

**Goals:**
- RFQ→报价→矩阵→定标的可演示闭环，三类卡控（MIN/异常/不足）与锁价状态机全部可验证
- 紧急放行桩首次真实消费；含税单价跨税码域复用 trial

**Non-Goals:**
- 不生成 PO（2.3.1 消费 `awarded` 桩）；不做 2.2.2 招标 / 2.2.3 协议 / 2.2.4 独立比价矩阵页
- 不做在线发送实投递（邮件/门户桩）、供应商自动推荐与履约四维（数据源未建，桩列）
- 不做 PO 侧价控（BR-4.2-15~19 属 2.3）

## Decisions

### D1 迁移 030：四表
- `erp_proc_rfq`：ID、RFQ_NO 唯一（`RFQ-YYYYMMDD-NNN`，MAX 序号取号忽略软删沿 028/029 模式）、PR_ID（索引）、状态、QUOTE_DEADLINE（DATE）、SEND_MODE（OFFLINE/ONLINE）、SENT_DATE、TAX_CODE_NO（可空，矩阵含税试算输入，D4 口径）、TECH_NOTE（技术说明，附件桩）、EMERGENCY_FLAG、INSUFFICIENT_FLAG、WEIGHT_PRICE/WEIGHT_DELIVERY（定标时快照，INT）、AWARD_SUPPLIER_ID/AWARD_PRICE、ANALYSIS_NO/ANALYSIS_CONCLUSION、CLOSE_REASON、审计+DEL_FLAG+VER_NO
- `erp_proc_rfq_line`：RFQ_ID、LINE_NO、ITEM_CODE、QTY、REQ_DATE（PR 行快照）；唯一 (RFQ_ID, LINE_NO)
- `erp_proc_rfq_supplier`：RFQ_ID、SUPPLIER_ID；唯一 (RFQ_ID, SUPPLIER_ID)
- `erp_proc_quote`：RFQ_ID、SUPPLIER_ID（唯一 (RFQ_ID, SUPPLIER_ID)）、UNIT_PRICE DECIMAL(18,4)、LEAD_TIME_DAYS INT、MOQ、PAYMENT_TERMS、QUOTE_VALID_DATE、NEGOTIATED_PRICE/NEGOTIATE_NOTE（谈判后，可空）、ANOMALY_FLAG、ANOMALY_CONFIRMED（含确认人时间列）、EXCLUDED/EXCLUDED_REASON（剔除）、审计列

### D2 状态机与支撑
`RfqStateMachine`（procurement 包，矩阵同构）：
```
DRAFT → SENT → QUOTING → QUOTED_CLOSED → AWARDED
DRAFT|SENT|QUOTING → QUOTED_CLOSED（截止日 sweep：未发出/无报价同样锁价→走不足处置）
DRAFT|SENT|QUOTING|QUOTED_CLOSED → CLOSED（人工，原因必填）
延期：DRAFT|SENT|QUOTING 内截止日回写（非状态迁移，字段校验）
QUOTED_CLOSED 的 INSUFFICIENT 处置：CLOSED（作废重询）——AWARDED 前唯一出口
```
`RfqSupport`：transition（矩阵+乐观锁 verNo+1+log）、persist（截止日/标记/定标字段）、publishHead（键 `RFQ单号:HEAD:vN`，v=verNo+1）、nextRfqNo。
- 懒 sweep（列表/详情前置）：`DRAFT|SENT|QUOTING` 且 `QUOTE_DEADLINE < today（工作日口径：businessDaysBetween(deadline, now) >= 1）` → `QUOTED_CLOSED` + LOCKED 事件；锁价后统计有效报价数（未剔除）< MIN 且 clearance 无有效放行 → `INSUFFICIENT_FLAG=1`

### D3 创建与紧急放行接入
`create(prId, supplierIds, deadline?, sendMode, techNote)`：
1. PR 必须 `PENDING_RFQ`；同 PR 未关闭 RFQ 防重（status != CLOSED）
2. 供应商全部 QUALIFIED（任一否则 422 指明哪家）
3. 数量校验：`clearance(prNo)`（EmergencyService 注入——**注意循环依赖**：EmergencyService 依赖 ProcRequisitionDao 不依赖 RfqService → 无环，直接注入）→ emergency 有效（status ∈ APPROVED_EMERGENCY/FILLING/COMPLETED 且非例外）→ minCount=1 且 EMERGENCY_FLAG=1；否则 minCount=MIN_QUOTE_COUNT；`supplierIds.size() < minCount` → 422（区分两文案：无放行提示扩充/准入；有放行但 0 家 422）
4. 截止日：默认 = `min(所有 PR 行 reqDate) minus 5 个工作日`（`plusBusinessDays` 反向→用 minusDays(1)循环? 简化：`minReqDate.minusDays(7)` 近似5工作日? **不**——正确实现：`plusBusinessDays` 只支持向前；写 `minusBusinessDays` 小工具（反向跳周末）到 RequisitionStateMachine——加方法，10 行）
5. 行快照复制 PR 行（item/qty/reqDate）、插 RFQ/行/供应商三表、CREATED 事件
- 手改截止日允许（创建参数传入则覆盖默认）

### D4 报价与矩阵计算
- `saveQuote(rfqId, supplierId, payload)`：状态 ∈ SENT/QUOTING（否则 422 文案区分草稿/锁价）、字段校验（单价>0 四位小数、交期>0、MOQ>0、付款条件枚举、有效期日期）、upsert（唯一键）、若 SENT → transition QUOTING、`PROC.RFQ.QUOTED` 事件（键含供应商序? 头事件 v 按 verNo+1 → 同状态多次报价事件键冲突！**报价事件键用 `RFQ单号:Q{supplierId前8}:v{verNo+1}`**——每次 saveQuote 前 persist(verNo+1) 再发（报价动作也递增头 verNo，与 transition 一致）；QUOTING 迁移事件另发 `RFQ单号:HEAD:vN`——同一次 save 两次 persist 会出两个 v，先 persist(计数) 发 QUOTED、再 transition 发 QUOTING → 两个键不同 ✓）
- 矩阵 `matrix(rfqId, weightPrice, weightDelivery)`（计算入参即时算不落库，定标时落快照）：
  - 有效报价 = 未剔除；均值/±20% 异常（无确认标记则 anomalyFlag 回显）；异常处理状态从 quote 列读
  - 含税：`MdmTaxCodeDao.selectHit(taxCode, rfqCreateDate)` —— **税码编码哪来**？含税试算需税码编号——PR 行无税码。规格含税对比默认有税码体系…实现口径：**用税率试算输入 = RFQ 行物料关联税码？物料无税码字段**。修正：按「RFQ 创建日 + 统一税率」无从取。真实可行口径：调 trial 需要 taxCode——无映射 → **矩阵提供税率输入（默认取税率试算按钮：用户输入税码编号或税率%）**? 或简化：含税单价按**可选税码编号输入**（RFQ 头存 TAX_CODE_NO 可空）→ trial 命中算含税，未填/未命中「待税率」。**design 决策：RFQ 头加可空 `TAX_CODE_NO` 列**（创建/详情可填），矩阵据此试算。
  - 加权：价格分/交付分公式按 spec；权重校验和=100（matrix 入参校验，422）
- `excludeQuote(quoteId, reason)` / `confirmAnomaly(quoteId)` / `negotiate(quoteId, price, note)`（原始保留，NEGOTIATED_* 列 + log）
- `postpone(rfqId, newDeadline, reason)`：状态未锁价、newDeadline > 当前、原因≥2；`addSuppliers(rfqId, ids)`：未锁价、全 QUALIFIED、并集 ≥1、追加后须重新线下发出（log 桩）

### D5 定标
`award(rfqId, supplierId, analysisNo, conclusion, weightPrice, weightDelivery)`：
- 前置链（按 spec）：状态 QUOTED_CLOSED（否则 422 区分未锁价/已定标）、INSUFFICIENT_FLAG=0 且有效报价 ≥ minCount（或 emergency 放行）、中选报价存在且未剔除、全部异常已确认或剔除（查 quote ANOMALY_FLAG=1 && !CONFIRMED && !EXCLUDED → 422）、分析表字段、权重和=100
- persist 定标字段 + transition AWARDED + `PROC.RFQ.AWARDED` 事件（diff=中选供应商名/成交价/分析编号）
- `awarded(prNo)` 查询桩（RFQ 头字段直读，无 → 明示结构）

### D6 前端
`views/proc/rfq-comparison/index.vue`：
- 列表：RFQ 单号/PR/状态/紧急/不足/截止日/报价进度（x/N）/操作详情
- 创建对话框：PR 下拉（PENDING_RFQ + 显示已有紧急放行徽标）、供应商 QUALIFIED 多选、截止日（默认回显）、发送模式、税码编号（可选，含税用）、技术说明
- 详情抽屉：行快照表、供应商清单+发出按钮、报价录入表格（行=供应商，字段内联编辑）、**矩阵区块**（权重输入+含税列+桩列+异常徽标与确认/剔除按钮+谈判列+总分排序）、异常提示条、延期/追加/作废重询按钮、定标表单（中选单选+分析编号/结论+异常未处理阻断提示）
- zhangsan：详情只读（写按钮 isAdmin 门控）

## Risks / Trade-offs

- [TaxCodeNo 口径（含税试算无物料→税码映射）] → RFQ 头可空税码编号输入 + 未填「待税率」；规格未规定映射，解释性口径入偏差
- [QUOTED/QUOTING 双事件同次保存] → D4 键设计（Q 前缀 + 独立 persist），冒烟验证无撞键
- [紧急 clearance 注入循环依赖] → EmergencyService→ProcRequisitionDao 单向，RfqServiceImpl 注入其接口安全；apply 时启动验证
- [锁价判定工作日口径 vs 截止日 DATE] → `businessDaysBetween(deadline, now)>=1` 即过期（含当天后首工作日），与 EA 一致
- [作废重询后 PR 仍 PENDING_RFQ 可重建 ✓]；AWARDED 后 PR 关联第二 RFQ 防重（AWARDED 非 CLOSED → 防重命中 ✓ 符合语义）

## Migration Plan

迁移 030 追加末尾编号；部署即生效；回滚 = 还原路由/页面/后端新增，表留存无害。

## Open Questions

（无——范围到定标、录入形态、矩阵深度、紧急接入均已经需求确认；含税税码编号输入与作废重询口径为 design 决策已记偏差）
