# Tasks

## 1. 采购域基础设施（迁移 028 + 状态机）

- [x] 1.1 迁移 `028-purchase-requisition.sql`（requisition 头/PR 行/交付计划行/审批任务四表，PR_NO 唯一、(PR_ID,LINE_NO)、审批 (PR_ID,NODE_NO,SUBMIT_BATCH) 索引）+ 四实体/DAO + `com.erp.procurement` 包骨架；验证：启动建表、重复启动幂等
- [x] 1.2 `RequisitionStateMachine` 迁移矩阵单点（D2 全部边：确认/预算/审批/驳回重提/补供应商流转/三类关闭/行级关闭）+ `transition()` 统一入口（校验+头 VER_NO 递增+留痕）；验证：正反例断言冒烟（每状态一合法一边非法，非法 422 带口径）
- [x] 1.3 SecurityConfig 追加 `POST/PUT/DELETE /api/proc/**` ADMIN + 构建通过 + 启动验证；验证：ROLE_USER 写 403、读 401、`mvn -q -DskipTests package` 通过

## 2. 自动请购后端（2.1.1）

- [x] 2.1 模拟净算 `preview`/`generate`：四类输入公式 NetReq、过量供给/逾期标记（dry-run 不落库）、>200 行 422、行级生成（**BR-4.2-07 物料 STATUS≠'1' 阻断带状态提示**、单号 `PR-YYYYMMDD-NNN`、来源枚举+单号、MRP 建议量存基线、`PROC.PR.CREATED` 事件键 `PR单号:L行号:vN`）；验证：curl 净算三态、Active 阻断、单号流水、事件入 outbox
- [x] 2.2 确认流：行级编辑保存（数量/日期/调减理由+复核人）+ 整单 `confirm`（**逐行 80% 校验**：不足须理由≥2字+复核人否则 422 指明行号）→ CONFIRMED + 事件 + 确认留痕；验证：curl 79% 阻断、调减复核放行、日期调整入留痕
- [x] 2.3 催办/升级懒 sweep（跳周末工作日 +1/+3 置 REMIND/ESCALATE 标记+扫描时间，通知 TODO 桩 log）+ 交付计划行 CRUD（Σ≤行需求 422、已关闭行禁维护）+ 关闭（手工必填原因、**90 天懒 sweep 自动关闭 C-4.2-11**）+ `receivePoAllocation` 反写桩（达量关行、超量 BR-4.2-51 阻断、全行关→头关）+ 补供应商与流转（无供应商阻断、成功→PENDING_RFQ+事件）；验证：curl 各分支

## 3. 手工请购后端（2.1.2）

- [x] 3.1 手工创建（需求来源/预算来源三选一、成本中心下拉校验、理由≥2字、物料 Active L1、**预算三空→PENDING_BUDGET 并禁流转（BR-4.2-09）**、补录重提→PENDING_APPROVAL、审批中 422 禁编辑、删除限可改态）；验证：curl 必填/预算标记/补录迁移/审批中禁改/403

## 4. 请购审批后端（2.1.4）

- [x] 4.1 提交审批与路由：判级金额 Σ(qty×estUnitPrice)、三档限额（@Value 50000/500000）、生成节点任务序列（DEPT_MANAGER→DIRECTOR→VP）→ APPROVING + 审批日志；可提交态聚合（PENDING_APPROVAL/CONFIRMED/PENDING_MODIFY）；验证：curl 三档路由各断言、金额计算
- [x] 4.2 通过/驳回/防重/重提：通过→下一节点或 APPROVED+事件；驳回（原因≥2字）→PENDING_MODIFY+REJECTED+任务 SUPERSEDED；非 ACTIVE 任务动作 422 防重；重提 SUBMIT_BATCH+1 新序列旧批次保留；验证：curl 逐级通过、驳回退回、重复动作拒绝、重提批次
- [x] 4.3 审批超时升级 sweep（ACTIVE 任务跳周末 +1 TIMEOUT_FLAG/+3 ESCALATE_FLAG+升级对象+日志）+ 待办查询（判级金额/路由链/标记/同物料历史预估价辅助）+ 按 PR 审批日志；验证：curl 标记落库、辅助信息、已批准不出待办

## 5. 前端三页面

- [x] 5.1 `views/proc/auto-requisition/index.vue`：Tab1 净算（表单/粘贴→建议列表红绿标→勾选生成→报告）；Tab2 PR 列表+行抽屉（确认编辑 80% 反馈、交付行、补供应商、流转、关闭、催办/升级/异常标记列）+ `api/proc/requisition.js`；验证：vite 无错、页面可达
- [x] 5.2 `views/proc/manual-requisition/index.vue`：创建表单（三选一 radio+成本中心下拉+预算三选一）、列表（待预算确认提示/编辑/删除/提交审批入口）；验证：同上
- [x] 5.3 `views/proc/requisition-approval/index.vue`：待办（判级金额/路由链/超时升级标记/历史预估价）+ 通过/驳回弹窗 + 审批日志抽屉 + 重新提交入口 + `api/proc/approval.js`；`router/index.js` 注册 `/m/2.1.1|2.1.2|2.1.4`；验证：三路由可达、菜单点击进入

## 6. 端到端验证

- [x] 6.1 接口冒烟全量：净算三态/Active 阻断/单号流水、80% 正反例、催办升级标记、交付行 422、90 天关闭、回写达量/超量、手工预算阻断与补录、三档路由/驳回/防重/重提、超时升级、401/403/ROLE_USER；全部断言通过
- [x] 6.2 浏览器全流程：admin → 2.1.1 净算生成（含过量与逾期各一行）→ 确认（80% 阻断演示→改量通过）→ 2.1.4 提交审批（三档路由验证）→ 通过/驳回重提 → 已批准 → 补供应商流转待询价 → 2.1.2 手工（待预算确认→补录→提交）→ 交付行与关闭演示；zhangsan 三页只读无写入口；截图留证、无 pageerror
- [x] 6.3 数据清理归种子态（proc 四表 + outbox PROC.PR 清空、参数配置还原）+ 兄弟回归（1.6.x/1.5.x 页面正常）+ `openspec validate add-purchase-requisition --strict` 通过 + 勾选全部任务
