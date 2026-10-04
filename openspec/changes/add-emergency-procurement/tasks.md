# Tasks

## 1. 基础设施（迁移 029 + 状态机）

- [x] 1.1 迁移 `029-emergency-procurement.sql`（EA 头表 EA_NO 唯一/PR_ID 索引/APPLICANT 列/特批与补齐与标记列 + 通道表 ACCOUNT 唯一，IF NOT EXISTS 幂等）+ 实体/DAO ×2；验证：启动建表、重复启动幂等
- [x] 1.2 `EmergencyStateMachine` 迁移矩阵（D3 全边）+ `EmergencySupport`（transition 乐观锁/事件键 EA单号:HEAD:vN/单号 MAX 序号忽略软删+唯一重试）；验证：jshell 正反例（每状态一合法一边非法）+ 单号重试路径
- [x] 1.3 SecurityConfig `proc/**` 覆盖核对（curl 403 实测）+ 构建通过；验证：ROLE_USER 写 403、读 401、`mvn -q -DskipTests package` 通过

## 2. 紧急申请后端（2.1.3 主体）

- [x] 2.1 发起 `create`：通道 BLOCKED → L1 拒绝（带原因/恢复提示，先校验不产生单据）→ PR 状态 APPROVED/PENDING_RFQ 校验 → 同 PR 未关闭 EA 防重 → 单号生成+插入+`CREATED` 事件；PR 候选查询（带判级金额/行摘要）；验证：curl 三类阻断各 422 + 正常创建 200 事件入 outbox
- [x] 2.2 特批 approve/reject：通过（原因≥2字→APPROVED_EMERGENCY+特批留痕+放行截止日=特批日+`EMERGENCY_FILL_DAYS`(`@Value` 默认5) 跳周末+`SPECIAL_APPROVED` 事件）、驳回（→REJECTED+事件）、状态防重 422；验证：curl 通过/驳回/重复拒绝/截止日计算（跨周末）
- [x] 2.3 补齐 fill（口径定案：受理态 = APPROVED_EMERGENCY 或 FILLING，动作内先迁 FILLING 再迁 COMPLETED，`FILLED` 事件仅发一次）：报价家数≥1、资料编号必填、补充说明、附件桩说明、EXCEPTION_FLAG 关闭、`COMPLETED` 终态；OVERDUE_EXCEPTION/REJECTED/COMPLETED/CLOSED → 422；验证：curl 正常完成/家数 0 阻断/逾期拒绝/重复补齐拒绝
- [x] 2.4 懒 sweep：特批超时（≥1 REMIND、≥3 ESCALATE 独立置位）+ 逾期（截止日跳周末超 fillDays → OVERDUE_EXCEPTION+EXCEPTION_FLAG+`OVERDUE` 事件+**通道 BLOCKED 落台账**+`[TODO-COMPLIANCE]` 桩）；验证：SQL 回填日期 → 查询触发 → 状态/通道/事件三断言
- [x] 2.5 通道台账 API：列表（含无行账号「已开通（默认）」语义与关联逾期数、筛选分页）、复核恢复（说明≥2字→OPEN+复核留痕+关闭该账号全部 OVERDUE_EXCEPTION EA+`CLOSED` 事件；OPEN 执行 422）、clearance 桩（有/无放行两态结构）；验证：curl 恢复闭环/422/桩两态

## 3. 前端页面

- [x] 3.1 `api/proc/emergency.js` + `views/proc/emergency-procurement/index.vue` Tab1（发起对话框 PR 候选/事由枚举、列表标记列、详情抽屉 特批/补齐/放行信息）；验证：vite 页面可达
- [x] 3.2 Tab2 通道台账（状态/阻断/复核/关联逾期数 + 复核恢复对话框）+ `router/index.js` 注册 `/m/2.1.3` + 写按钮 isAdmin 门控；验证：路由 200、非管理员无写入口
- [x] 3.3 政策/请购兄弟页回归：2.1.1/2.1.2/2.1.4 页面正常；验证：浏览器快速过三页无 pageerror

## 4. 端到端验证

- [x] 4.1 接口冒烟全量：三类发起阻断、特批通过/驳回/防重、截止日跨周末计算、补齐正反例、特批超时双标记、逾期联动（状态+通道+事件）、恢复关例外、clearance 两态、401/403/ROLE_USER；全部断言通过
- [x] 4.2 浏览器全流程：admin → 2.1.1 造已批准 PR（复用既有链路快速走）→ 2.1.3 发起 → 特批通过（截止日核对）→ 补齐登记 COMPLETED → 第二单走 逾期路径（SQL 回填触发 sweep：例外+通道 BLOCKED）→ 通道台账复核恢复（例外关闭）→ clearance 桩展示；zhangsan 无写入口；截图留证、无 pageerror
- [x] 4.3 数据清理归种子态（EA/通道表 + outbox PROC.EMERGENCY 清空、PR 域还原全 0）+ 兄弟回归（2.1.1/2.1.2/2.1.4/1.6.x 页面正常）+ `openspec validate add-emergency-procurement --strict` 通过 + 勾选全部任务
