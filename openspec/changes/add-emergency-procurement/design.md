# Design

## Context

- `add-purchase-requisition` 已交付：PR 状态机/事件支撑（`ProcRequisitionSupport` 模式）、`RequisitionStateMachine.plusBusinessDays/businessDaysBetween`（跳周末工作日）、`ProcRequisitionDao`（PR 候选与状态查询）、SecurityConfig `POST/PUT/DELETE /api/proc/**` ADMIN、审批表（本变更**不**复用，见 D2）
- 规格：S-4.2-04、BR-4.2-11（特批放宽 1 家报价 + EMERGENCY_FILL_DAYS 补齐 + 逾期处置）、参数 `EMERGENCY_FILL_DAYS=5`（L291）、L1321 异常场景（阻断创建权限 + 例外清单 + 复核恢复）
- 2.2 RFQ / 2.3 PO / 合规域例外清单均未建 → clearance 桩与清单推送 TODO

## Goals / Non-Goals

**Goals:**
- 特批 → 放行 → 5 工作日补齐 → 逾期阻断通道 → 总监复核恢复+关例外 的可演示闭环
- 通道「无行=开通」轻量语义，避免强制预开通流程

**Non-Goals:**
- 不做 RFQ/PO 侧放行消费（桩接口先行，2.2/2.3 接入）
- 不做附件文件上传（MinIO 未落地）；不做合规域例外清单实体（EA 例外标记 + TODO 桩）
- 不做采购员角色细分（统一 ADMIN 执行特批/复核，沿 2.1.4 口径）

## Decisions

### D1 迁移 029：两表
- `erp_proc_emergency`：ID、EA_NO 唯一（`EA-YYYYMMDD-NNN`）、PR_ID（索引）、REASON_TYPE（STOP_LINE/CUSTOMER_RUSH/OTHER）、REASON_DESC、EXPECT_ARRIVE_DATE、STATUS、特批留痕（SPECIAL_APPROVE_BY/SPECIAL_APPROVE_DATE/SPECIAL_REASON、REJECT_REASON）、放行（CLEARANCE_DUE_DATE）、补齐（QUOTE_COUNT/COMPARE_DOC_NO/FILL_NOTE/FILL_DATE）、标记（REMIND_FLAG/ESCALATE_FLAG/EXCEPTION_FLAG + SCAN_DATE）、申请人账号列 APPLICANT（沿 createBy，但需跨行聚合阻断查询 →冗余列 APPLICANT 账号，创建时 = SecurityUtils.getCurrentUserId()）、审计+DEL_FLAG+VER_NO（乐观锁与事件 v 基数）
- `erp_proc_emergency_channel`：ID、ACCOUNT（唯一）、STATUS（OPEN/BLOCKED）、BLOCK_REASON/BLOCK_DATE/BLOCK_EA_NO、REVIEW_BY/REVIEW_DATE/REVIEW_NOTE（恢复留痕）、审计+DEL_FLAG
- 索引：EA_NO 唯一、(PR_ID)、(STATUS)、channel ACCOUNT 唯一

### D2 特批不复用审批表
单节点无批次/序列语义，复用 `erp_proc_pr_approval` 会污染其 (PR_ID, SUBMIT_BATCH, NODE_NO) 契约与 prId 语义（表名 proc_pr_* 但存 EA id）→ 特批人/原因/时间直接落 EA 头列（L5 留痕 = 字段 + log + 事件）；特批超时/升级标记复用 EA 表 REMIND/ESCALATE 列。备选（审批表加 DOC_TYPE 列）需 MySQL 无条件 ALTER 且改既有唯一键语义——弃。

### D3 状态机与事件（单点）
`EmergencyStateMachine`（procurement 包，与 RequisitionStateMachine 同构）：
```
PENDING_SPECIAL_APPROVAL → APPROVED_EMERGENCY / REJECTED
APPROVED_EMERGENCY → FILLING
FILLING → COMPLETED
(特批中超时仅打标不迁移)
APPROVED_EMERGENCY|FILLING → OVERDUE_EXCEPTION   (sweep)
OVERDUE_EXCEPTION → CLOSED                        (总监复核恢复联动关闭；补齐禁走此路径)
任意非终态 → CLOSED                               (人工关闭，原因必填)
REJECTED / COMPLETED / CLOSED = 终态
```
- `EmergencySupport` 组件（仿 ProcRequisitionSupport）：`transition()`（矩阵+乐观锁 verNo+1+log）、`publishHead()`（键 `EA单号:HEAD:vN`，v=verNo+1）、`nextEaNo()`（MAX 序号忽略软删 + 唯一索引重试）
- 事件：CREATED（创建时 v1）、SPECIAL_APPROVED、REJECTED、FILLED、OVERDUE；**复核恢复关闭例外**复用 `PROC.EMERGENCY.OVERDUE`? 不——恢复关闭事件用 `PROC.EMERGENCY.CLOSED`（补第 6 类，proposal 已列 CLOSED 之外追加——proposal 写了 5 类，恢复关闭也需要可见性 → 追加 `PROC.EMERGENCY.CLOSED`，回填 proposal）

### D4 发起与通道校验顺序
`create(payload)`：① 通道校验（channel 查 ACCOUNT=申请人 且 BLOCKED → 422 带原因/恢复提示）→ ② PR 状态校验（APPROVED/PENDING_RFQ，查 `ProcRequisitionDao`）→ ③ 同 PR 未关闭 EA 防重（status NOT IN (REJECTED, CLOSED)）→ ④ 插入（单号重试）+ CREATED 事件。**顺序保证**：被阻断时不产生任何单据。

### D5 时限与 sweep（懒，页面查询触发）
- 参数 `@Value("${app.proc.emergency-fill-days:5}")`
- `sweep()` 于列表/详情/clearance 查询前执行：
  1. **特批超时**：`PENDING_SPECIAL_APPROVAL` 按 CREATE_DATE 跳周末 ≥1 → REMIND_FLAG、≥3 → ESCALATE_FLAG+SCAN_DATE（独立 if，防 add-purchase-requisition 曾踩的 else-if 互斥坑）
  2. **逾期**：`APPROVED_EMERGENCY|FILLING` 且 `businessDaysBetween(CLEARANCE_DUE_DATE 的日期, now) >= 1`（截止日本身已=特批日+fillDays，**不得再叠加 fillDays**——原稿双重计数，实施修正）→ transition OVERDUE_EXCEPTION + EXCEPTION_FLAG=1 + publish OVERDUE + **通道 BLOCK**（channel upsert BLOCKED：无行 insert / 有行置 BLOCKED 并记 BLOCK_*；OPEN 行保留复核历史列）+ `[TODO-COMPLIANCE]` log（例外清单推送桩）
- 补齐截止日 = `plusBusinessDays(特批日, fillDays)`
- 恢复动作：channel BLOCKED → 校验复核说明 → 置 OPEN+复核列 → 查该账号 APPLICANT 的 OVERDUE_EXCEPTION EA 逐个 transition CLOSED（原因「总监复核恢复，通道解除」+ publish CLOSED）

### D6 前端双 Tab + 门控
- `views/proc/emergency-procurement/index.vue`：Tab1 申请（发起对话框：PR 候选下拉[APPROVED/PENDING_RFQ]、事由枚举、说明、期望到货日；列表列 状态/特批信息/截止日/逾期天数/标记；详情抽屉 特批通过（原因）/驳回/补齐登记/放行信息；发起前读取本人通道状态提示）
- Tab2 通道台账：列表（账号/状态/阻断/复核/关联逾期数）+ 筛选分页 + 复核恢复对话框（说明必填）
- `api/proc/emergency.js`：create/approve/reject/fill/sweep 触发即列表查询、channels 列表、restore、clearance
- 路由 `/m/2.1.3`；写按钮 isAdmin 门控（沿既有模式）；SecurityConfig 复核既有 `proc/**` 规则（**apply 时 curl 实测 403 覆盖，同 add-tax-policy-workbench D6 核对点**）

## Risks / Trade-offs

- [APPLICANT 冗余列 vs createBy] → 冗余列便于阻断/例外聚合查询；与 createBy 同值写入，记 D1
- [通道恢复用「置 OPEN 行」还是删行] → 保留行+复核列（spec 两者皆可），展示复核历史更有审计价值
- [OVERDUE 后补齐被禁与 spec「补齐+复核恢复」时序] → spec 场景为 逾期后先复核恢复再处理；恢复直接 CLOSED 例外 EA，如需事后补登记可由总监在 CLOSED 前人工判断——记：恢复即关闭（不做"恢复后回到 FILLING 补齐"路径，闭环以关闭收口，简化口径入 design）
- [同 PR 多轮紧急（前次 REJECTED 后重发）] → 防重仅排除 REJECTED/CLOSED ✓
- [029 幂等] → 仅 CREATE TABLE IF NOT EXISTS + UNIQUE，无裸 ALTER

## Migration Plan

迁移 029 追加末尾编号；部署即生效；回滚 = 还原路由/页面/后端新增，表留存无害。

## Open Questions

（无——载体、发起范围、通道闭环、补齐形态均已经需求确认；恢复即关闭的简化口径已记 D5）
