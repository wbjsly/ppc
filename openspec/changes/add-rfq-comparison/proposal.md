# Proposal

## Why

菜单 2.2.1「询价比价」为占位页，寻源域零代码。规格流程二（FR-4.2-2-1/2）定义了 RFQ 创建、报价收集、比价矩阵、异常与双轨留痕的完整链路，是采购从「已批准 PR」走向「PO」的必经环节；`add-purchase-requisition` 已把 PR 推进到「待询价」终点、`add-emergency-procurement` 留下了 `clearance?prNo=` 放行桩等待消费——本变更承接两者，打通询价→比价→定标闭环（PO 生成留给 2.3）。

## What Changes

### 功能一：RFQ 询价单

- **迁移 030 四表**：`erp_proc_rfq`（头：单号唯一、PR_ID、状态、截止日、发送模式/状态、EMERGENCY 标识、比价分析表编号/结论、头 VER_NO）、`erp_proc_rfq_line`（**PR 行快照**：物料/数量/交付要求——保存时复制，PR 后续变更不影响已发 RFQ）、`erp_proc_rfq_supplier`（RFQ×供应商清单，唯一）、`erp_proc_quote`（报价：RFQ×供应商唯一，单价/交货期/MOQ/付款条件/有效期、谈判后单价与说明、异常确认/剔除标记）
- **创建**：仅对 `PENDING_RFQ` 的 PR 生成（整单）；单号 `RFQ-YYYYMMDD-NNN` 按日流水；字段含物料快照、需求数量、交付日期要求、技术标准/图纸（附件桩）、报价截止日期（**默认 = PR 最早需求日 − 5 个工作日**——采购提前期字段缺失，偏差表记口径）
- **供应商选择**：仅 `QUALIFIED` 状态可选（多选）；历史合作自动推荐留桩（推荐列 = 桩标记，供应商评分/履约数据未建）
- **C-4.2-01 L1**：合格供应商数 < `MIN_QUOTE_COUNT`（`@Value` 默认 3）→ 创建 422，提示扩充供应商库或发起新供应商准入
- **紧急例外接入（clearance 桩消费）**：关联 PR 存在有效紧急放行（`clearance.prNo` 返回 emergency=true 且非例外）→ 允许 **1 家** 起建，RFQ 标 `EMERGENCY`；无放行且 <3 → 阻断
- **状态机**（单点矩阵，`RFQStateMachine`）：`DRAFT → SENT → QUOTING → QUOTED_CLOSED → AWARDED`，旁路 `CLOSED`
  - `send`（线下确认发出，SEND_MODE=OFFLINE；ONLINE 为发送状态桩）→ SENT
  - 首次录入报价 SENT → QUOTING
  - **截止日懒 sweep**：超报价截止日（跳周末口径）→ QUOTED_CLOSED（锁价）；锁价后报价禁改
  - 延期截止日（仅未锁价，新截止日 > 旧截止日，原因必填）；**追加供应商**（仅未锁价，追加后仍须 ≥1，重新计发送）
  - 锁价后报价数（含）< MIN 且无紧急放行 → RFQ 标 `INSUFFICIENT_FLAG`（BR-4.2-13）：**阻断定标** + 页面提供 延期/追加 入口（延期使锁价回退 QUOTING？——否：延期仅未锁价可用；已锁价不足 → 重询 = 作废本单 CLOSED + 从 PR 重建（简单闭环，design 记）
  - `award`（定标）→ AWARDED；人工关闭 → CLOSED（原因必填）
- **事件**：`PROC.RFQ.CREATED / SENT / QUOTED / LOCKED / AWARDED`（键 `RFQ单号:HEAD:vN` 含记录身份）

### 功能二：报价录入与收集

- 询价单详情内逐供应商录入：单价（4 位小数）、交货期（天，整数）、MOQ（数量）、付款条件（枚举：NET30/NET60/NET90/款到发货）、报价有效期（日期）；**SENT 后可录、DRAFT 禁录**；`QUOTED_CLOSED` 后禁改（422）；同供应商重复录入 = 覆盖更新（截止前）
- 报价状态懒校验：录入即更新 RFQ 报价计数（QUOTING 触发）

### 功能三：比价矩阵（RFQ 详情内）

- **实做维度**：单价、**含税单价 = 单价 ×(1+税率)**——税率按 RFQ 创建日调用 `erp_mdm_tax_code` 试算（跨域复用已有 trial，无税码/无覆盖 → 含税列「待税率」明示）、交货期（越短越优归一）
- **权重可调**：价格权重/交付权重（和为 100%，输入或滑块）→ 加权总分（价格分 = 最低价/本价×100，交付分 = 最短交期/本交期×100，缺交期按 0 分）→ 排序展示
- **桩列**：历史准时交付率、历史来料合格率、质量等级、供应商综合评分（显示「待接入 PO/质检数据」）
- **BR-4.2-12 L4 ±20% 异常**：单报价偏离有效报价均值 >20% → 标「异常偏离」；须逐条 **确认保留** 或 **剔除**（剔除 = 报价置无效不参与均值/定标）→ 未处理完 422 阻断定标
- **BR-4.2-14 L5 谈判双轨**：定标前可录入谈判后单价+谈判说明，原始报价与谈判后**并存**（矩阵双列显示，历史不覆盖）；加权总分以**谈判后单价（如有）**参与计算
- **比价分析表**：定标时必填「分析编号 + 结论文本」（文档生成桩，编号文本登记）

### 功能四：定标（终点，不生成 PO）

- `award(rfqId, supplierId, analysisNo, conclusion)`：前置 = 已锁价（QUOTED_CLOSED）、中选供应商有有效报价且未被剔除、异常报价全部确认/剔除、报价数 ≥ MIN 或有效紧急放行、分析表字段齐全 → `AWARDED` + `PROC.RFQ.AWARDED` 事件（diff 含中选供应商/单价）
- 中选结果查询桩：`GET /api/proc/rfqs/awarded?prNo=` 供 2.3.1 订单创建消费（返回 RFQ 单号、中选供应商、成交单价、紧急标识）
- 本变更**不生成 PO**（需求确认：到定标为止）

### 基础设施与页面

- 路由 `/m/2.2.1`；SecurityConfig `proc/**` 写规则已覆盖（核对 403）
- 页面 `views/proc/rfq-comparison/index.vue`：列表（状态/紧急标识/不足标记/截止日/报价进度）+ RFQ 详情抽屉（供应商清单与发送、报价录入表格、比价矩阵[权重+双列+异常徽标+桩列]、定标表单、延期/追加入口）+ 创建对话框（PR 候选 = PENDING_RFQ、供应商 QUALIFIED 多选、截止日默认、紧急放行提示）

## Capabilities

### New Capabilities

- `rfq-inquiry`: RFQ 创建约束（PR 状态/供应商资格/MIN 卡控/紧急放行例外）、状态机与截止日锁价、延期与追加供应商、报价录入与锁价保护、事件与权限
- `rfq-comparison-award`: 比价矩阵（含税单价跨域试算、权重加权、±20% 异常处理、谈判双轨）、比价分析表登记、定标前置校验与 AWARDED、中选结果查询桩

### Modified Capabilities

（无——PR/紧急/税码/供应商主数据仅被只读引用或桩消费）

## Impact

- 后端：迁移 `030-rfq-comparison.sql`（四表）；`com.erp.procurement` 增 `RfqStateMachine`/`RfqSupport`/`RfqService(Impl)`（创建/发送/报价/锁价 sweep/延期追加/矩阵/定标）+ Controller `/api/proc/rfqs`；复用 `MdmSupplierDao`(QUALIFIED)、`MdmTaxCodeDao.selectHit`（含税试算）、`EmergencyService.clearance`、`RequisitionStateMachine` 工作日、`ProcRequisitionDao`
- 前端：`views/proc/rfq-comparison/index.vue`、`api/proc/rfq.js`、路由 `/m/2.2.1`
- 不动：2.2.2/2.2.3/2.2.4、2.3 PO 生成、供应商评分/履约数据（桩列）

### 偏差表（规格 vs 当前落地）

| 规格条目 | 本变更口径 | 级别 |
|---|---|---|
| 截止日 = 需求日 −（采购提前期 + 5 工作日） | 提前期字段缺失 → 默认 = 最早需求日 − 5 工作日（可手改） | 解释性口径 |
| 在线发送（邮件/供应商门户） | SEND_MODE=ONLINE 仅记发送状态桩 | 桩（门户/邮件未建） |
| 系统自动推荐历史合作好/评级高供应商 | 推荐列桩标记；仅列 QUALIFIED 可选 | 桩（无评分/履约数据） |
| 矩阵交付/质量/综合四维度 | 交货期实做；准时率/合格率/评分=待接入桩列 | 简化口径（依赖 PO/质检） |
| 含税价格对比 | 按 `erp_mdm_tax_code` 试算（有税码覆盖才可算，否则「待税率」） | 跨域复用（已有 trial） |
| 比价分析表「保存为正式文档」 | 编号+结论文本登记，文档生成桩 | 载体桩 |
| `MIN_QUOTE_COUNT` 参数页 | `@Value` 配置默认 3（无参数管理页） | 沿 @Value 先例 |
| 选最优自动生成 PO（FR-4.2-3-1） | 不在本变更（2.3.1 消费 awarded 桩） | 范围边界（需求确认） |
