# Proposal

## Why

菜单 2.1.3「紧急采购」为占位页。规格 S-4.2-04（L1189）与 BR-4.2-11（L1214）定义了紧急采购的完整机制：简化询价（最低 1 家报价）须经**采购总监特批（L2）**、PO 标记紧急标识、`EMERGENCY_FILL_DAYS`（默认 5 个工作日）内补齐比价资料，逾期则**阻断该采购员紧急通道创建权限并列入合规例外清单**（L1321 异常场景），补齐+总监复核恢复。RFQ/PO（2.2/2.3）未落地，但特批放行、时限、例外、恢复的闭环可先行——`add-purchase-requisition` 刚交付的 PR 状态机与审批/懒 sweep 模式直接衔接。

## What Changes

### 功能一：紧急采购申请单（EA，独立单据）

- **发起**：仅对 `APPROVED` / `PENDING_RFQ` 状态的 PR 发起（候选列表带判级金额与行摘要）；紧急事由枚举（产线停线 / 客户紧急订单 / 其他）+ 事由说明 ≥2 字 + 期望到货日；单号 `EA-YYYYMMDD-NNN` 按日流水（唯一索引兜底）
- **发起前置（L1321 创建权限阻断）**：校验申请人紧急通道状态，`BLOCKED` → L1 硬阻断并展示阻断原因/恢复路径；同一 PR 存在未关闭 EA（非 REJECTED/CLOSED）→ 422 防重
- **状态机**（单点矩阵，`DRAFT → PENDING_SPECIAL_APPROVAL → APPROVED_EMERGENCY → FILLING → COMPLETED`，旁路 `REJECTED` / `OVERDUE_EXCEPTION` / `CLOSED`）：
  - 创建即 `PENDING_SPECIAL_APPROVAL`（特批中）
  - **特批（采购总监单节点，L2 降级）**：通过 → `APPROVED_EMERGENCY`（记录特批人/时间/原因，生成放行记录：PR 单号 + 补齐截止日 = 特批日 + `EMERGENCY_FILL_DAYS`，参数 `app.proc.emergency-fill-days` 默认 5）；驳回（原因≥2字）→ `REJECTED`
  - **特批超时打标**（沿 BR-4.2-10 模式）：特批中超 1 工作日 → 提醒标记；超 3 工作日 → 升级标记（懒 sweep，通知 TODO 桩）
  - `APPROVED_EMERGENCY → FILLING`（开始补齐计时确认）→ 补齐登记 → `COMPLETED`
  - **逾期懒 sweep**：放行后跳周末工作日 > `EMERGENCY_FILL_DAYS` 未补齐 → `OVERDUE_EXCEPTION` + 例外标记 + **申请人通道置 BLOCKED**（原因/时间落通道台账）+ `PROC.EMERGENCY.OVERDUE` 事件 + 合规清单推送 TODO 桩
- **比价补齐（文本登记 + 附件桩）**：实际报价家数（≥1）、比价资料编号、紧急事由补充说明、登记时间；附件上传留桩（MinIO 未落地）；`COMPLETED` 时关闭该申请的例外标记
- **RFQ/PO 放行桩**：`GET /api/proc/emergency/clearance?prNo=` 返回该 PR 的紧急放行（特批状态/截止日/紧急标识），供 2.2 转 PO 校验「1 家报价放行」与 2.3 PO 紧急标记消费（BR-4.2-11 落地时接入），当前无调用方但可独立验证
- outbox `PROC.EMERGENCY.CREATED / SPECIAL_APPROVED / REJECTED / FILLED / OVERDUE / CLOSED`（CLOSED=复核恢复关例外与人工关闭），幂等键 `EA单号:HEAD:vN`（N=头 VER_NO+1，含记录身份沿 D3 口径）

### 功能二：紧急通道台账

- 新表 `erp_proc_emergency_channel`（按登录用户账号唯一）：**无行 = 已开通（OPEN，隐式语义）**；`BLOCKED` 记录阻断原因/时间/来源 EA；**总监复核**（必填复核说明）→ 恢复 OPEN + 关闭该用户全部逾期例外（`OVERDUE_EXCEPTION → CLOSED`，L1321 恢复路径）+ 操作留痕
- 台账页展示：账号、状态、阻断原因、阻断时间、复核人、关联逾期 EA 数

### 功能三：页面 `/m/2.1.3`（不新增菜单）

- Tab1 紧急申请：发起（PR 候选选择 + 事由）→ 列表（状态/特批信息/补齐截止日/逾期天数/例外标记/超时升级标记）→ 详情抽屉（特批通过/驳回、补齐登记、放行信息、事件可见）
- Tab2 通道台账：状态列表 + 总监复核恢复入口
- 写操作 ADMIN 门控（SecurityConfig 已有 `proc/**` 规则覆盖）；发起入口按通道状态可见性提示

### 基础设施

- **迁移 029**：`erp_proc_emergency`（EA 头：单号唯一、PR 外键、事由枚举/说明、状态、特批留字段 SPECIAL_APPROVE_BY/REASON/DATE、放行 CLEARANCE_DUE_DATE、补齐字段 QUOTE_COUNT/COMPARE_DOC_NO/FILL_NOTE/FILL_DATE、超时/升级/例外标记、头 VER_NO）+ `erp_proc_emergency_channel`（账号唯一、状态、阻断/复核留痕）
- 特批不复用审批表（单节点无序列语义，留 EA 表字段——简化决策记 design）
- `ProcRequisitionSupport` 模式复用（transition/事件/单号），新建 `EmergencySupport` 同构实现

## Capabilities

### New Capabilities

- `emergency-procurement`: EA 发起约束（PR 状态/通道阻断/防重）、特批状态机与放行记录、特批超时升级标记、比价补齐登记、逾期 sweep 与通道阻断、复核恢复闭环、RFQ/PO 放行桩、权限口径
- `emergency-channel`: 通道台账语义（无行=开通、BLOCKED 阻断创建、复核恢复+关例外）、展示与留痕

### Modified Capabilities

（无——PR/审批域仅被只读引用）

## Impact

- 后端：迁移 `029-emergency-procurement.sql`；`com.erp.procurement` 增 `EmergencySupport`/`EmergencyService(Impl)`、EA Controller（`/api/proc/emergency`：发起/特批/补齐/clearance 桩 + `/api/proc/emergency-channels` 通道）；复用 `ProcRequisitionDao`（候选/校验）、`RequisitionStateMachine.businessDaysBetween`（工作日）、`@Value` 参数
- 前端：`views/proc/emergency-procurement/index.vue`（双 Tab）、`api/proc/emergency.js`、路由 `/m/2.1.3`
- SecurityConfig：`proc/**` 写规则已覆盖，核对即可
- 不动：2.2 RFQ、2.3 PO、合规域例外清单实体（TODO 桩）

### 偏差表（规格 vs 当前落地）

| 规格条目 | 本变更口径 | 级别 |
|---|---|---|
| BR-4.2-11 特批（L2，Flowable 类流程） | EA 表内采购总监单节点通过/驳回 + 懒 sweep 超时打标；通知/改派 TODO 桩 | L2 降级入偏差表（沿项目基线） |
| 「采购总监」角色执行 | 当前统一 ADMIN 执行，节点角色字段保留审计语义；角色细分留后续 | 解释性口径（沿 2.1.4 同款） |
| 补齐「完整比价资料」上传 | 文本登记（家数/编号/说明），附件上传留桩（MinIO 未落地） | 载体偏差（需求已确认） |
| PO 标记紧急标识 + 1 家报价放行 | clearance 桩接口先行，RFQ/PO 侧待 2.2/2.3 落地接入 | 范围边界 |
| 合规例外清单（跨域） | EA 上例外标记 + 通道台账呈现，跨域清单推送 TODO 桩 | 简化口径 |
| 通道按「采购员」 | 当前登录用户账号即采购员主体（无采购员实体） | 解释性口径 |
