# Design

## Context

- 采购域首变更：无既有 `com.erp.procurement` 代码；菜单 M2-1-1/2/4 已注册（099），路由未挂；迁移下一号 028
- 可复用：`MdmItemDao`（物料 `STATUS='1'` 即已发布启用，行 532 已确立该口径）、`MdmSupplierDao`、`MdmCostCenterDao`、`OutboxPublisher`、懒 sweep 模式（汇率/证照/信用同款）、`@Value` 配置参数先例（`app.mdm.group-credit-limit-ratio`）、SecurityConfig 显式路径 ADMIN
- 单据 vs 主数据：PR 是交易单据——沿供应商合并口径（操作日志+状态迁移留痕），**不套**主数据版本快照；但事件幂等键沿 tax-code D3「含记录身份」教训
- 规格：FR-4.2-1-1/2、BR-4.2-07/08/09/10/50/51、C-4.2-11、FR-4.5-2-3/5/6（净算公式）、S-4.2-01/02

## Goals / Non-Goals

**Goals:**
- 三菜单端到端：模拟净算→生成→确认→审批→待询价的完整可演示链路；状态机单点守卫；懒 sweep 两类（催办/90天关闭/审批超时）
- 真实上游（4.5/WMS/工单）与下游（2.2 询价、2.3 下达）以桩接口衔接，替换时不动状态机

**Non-Goals:**
- 不实现 4.5 MRP 引擎（BOM 展开/提前期倒排/时间桶滚动——本变更仅 FR-4.5-2-3 净额公式与标记语义）
- 不做 2.1.3 紧急采购、2.2 询价、2.3 PO 下达（留桩）
- 不做推送通知通道（TODO 桩，标记落库）；不做角色细分（采购员/计划员/经理账号），动作统一 ADMIN（偏差）
- 不做 MOQ 调整（物料无该字段）、不做参数管理页（@Value 配置）

## Decisions

### D1 迁移 028：四表（无主数据列变更）
- `erp_proc_requisition`（头）：ID、PR_NO 唯一、SOURCE_TYPE（MRP/MANUAL）、STATUS、需求来源三字段（REQ_PROJECT_NO/REQ_COST_CENTER_ID/REQ_INTERNAL_ORDER_NO）、预算来源三字段（BUDGET_SUBJECT/BUDGET_COST_CENTER_ID/BUDGET_INTERNAL_ORDER_NO）、REQ_REASON、REJECT_REASON、确认/关闭留痕列（CONFIRM_REASON、CONFIRM_BY、CLOSE_REASON 等）、催办升级列（REMIND_FLAG/ESCALATE_FLAG/SCAN_DATE）、审计+DEL_FLAG+VER_NO（乐观锁沿用）
- `erp_proc_pr_line`：ID、PR_ID、LINE_NO、ITEM_CODE、QTY、REQ_DATE、EST_UNIT_PRICE、SOURCE_ENUM（工单/计划计划订单/销售订单/安全库存/模拟）、SOURCE_DOC_NO、SUGGESTED_SUPPLIER_ID（可空）、MRP_SUGGESTED_QTY（80% 基数）、REVIEW_REASON/L2 复核列、LINE_STATUS（OPEN/CLOSED）、CLOSED_REASON、ALLOC_QTY（PO 反写累计，初 0）、异常标记列；唯一 (PR_ID, LINE_NO)
- `erp_proc_pr_delivery_line`：ID、PR_LINE_ID、DELIVERY_DATE、QTY、审计
- `erp_proc_pr_approval`：ID、PR_ID、NODE_NO、NODE_ROLE（DEPT_MANAGER/DIRECTOR/VP）、STATUS（ACTIVE/PASSED/REJECTED/SUPERSEDED）、ACTION_REASON、ACTED_BY、超时/升级标记+扫描时间、SUBMIT_BATCH（重提批次号，区分任务序列）、审计；PR 每次提交审批批次 +1
- 索引：PR_NO 唯一、(STATUS)、(PR_ID, LINE_NO)、审批 (PR_ID, NODE_NO)、(STATUS, SUBMIT_BATCH)
- 单号：`PR-YYYYMMDD-NNN` 按 PR_NO 前缀 count+1（同日唯一索引兜底，DuplicateKey→重试一次或 409）

### D2 状态机单点守卫
`RequisitionStateMachine`（procurement 包）以 `ALLOWED` 迁移矩阵单点校验，全部状态变更必须走 `transition(pr, to, reason)`（校验 + 递增头 VER_NO + 写事件/留痕）：
```
MRP:    PENDING_CONFIRM → CONFIRMED →(提交审批)→ APPROVING → APPROVED
                                                      ⇅ 驳回 → PENDING_MODIFY →(重提)→ APPROVING
MANUAL: PENDING_BUDGET →(补预算)→ PENDING_APPROVAL →(提交审批)→ APPROVING → APPROVED
APPROVED →(补供应商并流转)→ PENDING_RFQ（本变更终点态，2.2 接收）→ CLOSED
任意非 CLOSED → CLOSED（手工关闭 / 90 天 sweep / 全行下达完成）
行级：OPEN → CLOSED（手工关闭 / C-4.2-11 超期 / 下达完成）
```
- **留痕分工**：审批类动作（提交/通过/驳回/重提/超时/升级）入 `erp_proc_pr_approval` 审批日志（L5）；一般操作（确认/补预算/关闭/交付行变更/流转）= `log.info` + 头/行留痕列（确认人、关闭原因等），沿供应商合并「日志+字段」口径，不建独立操作日志表
- 确认粒度 = **整单一键确认**（页内先逐行编辑数量/日期/复核标记并保存，80% 按行各自判定），头状态 CONFIRMED 带确认人/时间列

### D3 幂等键与事件
`bizKey = PR单号 + ":" + 行号或"HEAD"` → outbox 键 `PR单号:行号:vN`（N = 事件序=快照式计数？无版本表 → 用动作序号：以 outbox 内该键 count+1？不能查询后拼——**N 取 PR 的 VER_NO**：每次头级写操作 verNo+1（乐观锁副产品），行级事件用 `行号:v{行VER_NO}`？行表无 VER_NO……
简化：**N = PR 头 VER_NO**（所有动作都在头写路径上递增 verNo；行级动作先递增头 verNo 再发事件，记录 VER_NO 快照）→ 同一动作重放=同键 409 ✓。行事件键 `PR单号:L{行号}:v{头VerNo}`，头事件 `PR单号:HEAD:v{头VerNo}`。
事件类型：`PROC.PR.CREATED / CONFIRMED / SUBMITTED / APPROVED / REJECTED / CLOSED / ROUTED`（7 类，白名单沿最小披露）。

### D4 模拟净算与 PR 批量生成
`MrmSimulateService.preview(rows)`（dry-run）：行 {itemCode, demand, onHand, inProcess, inTransit, reqDate} → NetReq=公式 → 建议 {netReq, flag(OVER_SUPPLY/OVERDUE/OK)}；物料查不到 → 行失败。`generate(rows)`：一张 PR 承载全部合法勾选行（行级校验失败行不阻断整批——失败行入报告，合法行 ≥1 才建头；沿批量模式：方法不加 @Transactional、>200 行上限 422）。批量粘贴 CSV 列：`物料编码,需求日期,需求量,现有库存,在制,在途,来源类型,来源单号,建议供应商编码`（**前 6 列必需**，来源类型缺省 SIMULATED、单号/供应商可空；供应商编码按主数据解析为 ID，未命中行失败）——补 D4 初稿遗漏的需求来源两列（spec 行模型必含），同一行录入四类输入与来源
- 生成后勾选行的确认基数 MRP_SUGGESTED_QTY = 生成时净需求

### D5 确认/80%/L2 复核
确认 = 整单动作（D2），后端 `confirm(prId)`：页内逐行已保存（数量/日期/复核标记），此处按行判定：`行 qty < 行建议量*0.8` 且该行（缺调减理由 或 缺复核人）→ 422 指明行号与缺口（BR-4.2-08）；全行通过 → 头迁移 CONFIRMED（确认人/时间列）+ `PROC.PR.CONFIRMED` 事件。
- 催办/升级懒 sweep：`CONFIRM_DATE`/`CREATE_DATE` 起算，**工作日 = 跳过周六日的自然日近似**（无节假日日历，偏差表记口径）→ 超 1 工作日置 `REMIND_FLAG`、超 3 工作日置 `ESCALATE_FLAG`+扫描时间列；推送通知 TODO 桩（log 占位）

### D6 分批交付行与关闭
- 交付行 CRUD：Σ.qty ≤ line.qty 校验；操作留痕 log + 行上 LAST_DELIVERY_CHANGE？
- 90天 sweep：`REQ_DATE + 90d < today && ALLOC_QTY=0 && LINE_STATUS=OPEN` → CLOSED（原因「C-4.2-11 超期自动关闭」+ 关闭时间列）
- `receivePoAllocation(lineId, qty)`：校验 OPEN、qty>0、ALLOC_QTY+qty ≤ QTY 否则 422（BR-4.2-51 文案）；累加；=QTY → 行 CLOSED（原因「下达完成」）；头级：全部行 CLOSED → 头 CLOSED？规格：达量自动关闭行；头状态联动 → 头所有行 CLOSED → 头 CLOSED（事件）。接口路径 `POST /api/proc/requisitions/lines/{id}/allocation`（桩，ADMIN）。

### D7 审批路由与任务
- **提交审批是审批页入口**（聚合可提交态：MANUAL 的 PENDING_APPROVAL、MRP 的 CONFIRMED、已驳回的 PENDING_MODIFY 重提）→ 按判级金额生成任务序列，头迁移 APPROVING；即所有 PR（MRP/手工）统一经审批到 APPROVED
- 判级金额 = `SUM(行 qty × estUnitPrice)`；路由三档（D 已述），节点 DEPT_MANAGER/DIRECTOR/VP 三档**记录**审计语义，动作执行者 = ADMIN（角色细分留后续，偏差）
- 任务防重：节点任务非 ACTIVE → 422「任务已处理」；驳回 → 未完成任务置 SUPERSEDED、新批次 `SUBMIT_BATCH+1` 重提（历史批次保留）
- 超时/升级 sweep：ACTIVE 任务按 `CREATE_DATE` 跳周末 +1/+3 → `TIMEOUT_FLAG`/`ESCALATE_FLAG`+扫描时间；升级对象=上一级（VP 无上级 → 保持总监备注），通知 TODO 桩
- 辅助信息：同物料历史 PR 预估价 = 按 ITEM_CODE 查历史行 `EST_UNIT_PRICE` 倒序取最近 5 条；库存可供量待 WMS（页内明示暂无，偏差已记）

### D8 前端三页 + 路由 + 权限
- `views/proc/auto-requisition/index.vue`：Tab1 模拟净算（粘贴/表单 → 预检列表红绿标 → 勾选生成 → 报告）；Tab2 PR 列表（头+行抽屉：确认编辑 80% 校验反馈、交付行管理、补供应商、流转、关闭、PO 回写调试入口?）——桩接口浏览器验证放 API 冒烟，页面不放
- `views/proc/manual-requisition/index.vue`：创建表单（来源/预算三选一 radio + 下拉）+ 列表（待预算确认提示、编辑/删除、提交审批入口放审批页？——提交入口双页都放（CONFIRMED/可提交态））
- `views/proc/requisition-approval/index.vue`：待办列表（判级金额/路由链/超时升级标记/辅助信息）+ 通过/驳回 + 审批日志抽屉 + 重新提交入口
- router `/m/2.1.1|2.1.2|2.1.4`；SecurityConfig `POST/PUT/DELETE /api/proc/**` ADMIN（首版全量，粒度与既有域一致）

## Risks / Trade-offs

- [工作日计算无节假日日历] → 跳周末近似 + 偏差表记口径
- [状态机矩阵遗漏边] → D2 矩阵 + 实现后全迁移路径断言冒烟（每状态至少一正一反用例）
- [模拟净算与真实 MRP 语义漂移] → 公式/标记严格按 FR-4.5-2 文本，接口分层（preview/generate）使替换仅在输入源
- [事件键 N=头 VER_NO 依赖每次动作递增] → transition() 统一入口内递增，禁止绕过
- [三菜单一体大变更] → tasks 按 菜单分组 + 共享基础设施先行，端到端验收清单覆盖交叉路径（手工→审批、MRP→确认→审批）
- [审批表兼作日志] → 审批表仅审批动作；一般操作 log+字段留痕（D2）

## Migration Plan

迁移 028 追加末尾编号；部署即生效；回滚 = 还原路由/页面/后端新增，表留存无害。

## Open Questions

（无——范围、状态机、80% 复核、判级口径、桩边界均已经需求确认或在 spec 场景固化）
