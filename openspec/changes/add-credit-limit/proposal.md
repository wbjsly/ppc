# Proposal

## Why

菜单 1.3.2「信用额度」指向占位页。规格 FR-4.1-6-3 与 BR-4.1-31/32/33 定义了额度调整的完整语义：法人额度调整即时生效、集团占用率超 80% 提示、临时额度到期自动回滚、调整记录永久回溯——客户准入（020）已建信用字段与求和校验，但**无调整入口、无临时额度、无占用率视图、无年度复审台账**。本轮补齐信用额度全生命周期管理，为销售域信用检查（FR-4.3-2-x）提供数据载体。

## What Changes

- **迁移 `021-credit-limit.sql`**：`erp_mdm_customer_view` 加列 `TEMP_CREDIT_LIMIT`（临时额度）、`TEMP_EXPIRE_DATE`（有效期，BR-4.1-33 必填）、`LAST_REVIEW_DATE`（年度复审日期，C-4.3-13 台账）；无新表（调整历史用版本快照承载）
- **法人额度调整**（FR-4.1-6-3 核心）：常规额度 + 临时额度（有效期必填 > 当天）；保存即校验 Σ ≤ 集团总额度×`GROUP_CREDIT_LIMIT_RATIO`（复用准入 422）；原因必填 → 版本快照（OP_TYPE=UPDATE 含额度 diff）+ `MDM.CUSTOMER.CREDIT_UPDATED` 日志桩
- **临时额度回滚**（BR-4.1-33）：`@Scheduled` 定时任务（每小时扫描到期临时额度 → 清零回滚 + 快照记录）+ 打开页面时懒校验兜底双保险
- **集团调整入口**：集团总额度/评级行内调整（改求和基准，原因必填入快照）
- **占用率**（BR-4.1-32）：列表/详情展示 `Σ法人额度 ÷ 集团总额度`，>80% 黄标提示（L4 不阻断）
- **时间轴历史**：客户详情复用版本快照时间轴，额度变化（旧→新+原因+操作人）逐条展示，零新表
- **年度复审台账**（C-4.3-13 降级版）：`LAST_REVIEW_DATE` 超 12 个月 → 红标「复审超期」+ 自动按 ratio 临时压缩可用额度（L3 自动入快照）；「复审通过」按钮更新日期并恢复
- **可用额度试算口径展示**：`可用 = 额度（含有效临时） - 应收 - 未清SO - 本单`（FR-4.3-2-2 公式页面明示，应收/未清SO 为桩「销售/财务域未接入」）
- 前端 `views/mdm/credit-limit/`、`router/index.js` 注册 `/m/1.3.2`；`SecurityConfig` 写接口 ADMIN

### 与规格的关系

| 规格条目 | 处理 |
|---|---|
| **FR-4.1-6-3 额度调整与同步（行 675-680）** | **补齐**（核心：调整+占用率+时间轴） |
| **BR-4.1-32 占用率>80% 提示（行 781）** | **补齐**（L4 黄标，不阻断） |
| **BR-4.1-33 临时额度到期回滚（行 782）** | **补齐**（定时+懒校验双保险） |
| **BR-4.1-31 求和超限（行 780）** | 已有（准入实现），调整入口复用同一校验 |
| S-4.1-03 信用管理员审核（行 716） | **降级**：无审批立即生效（沿基线，偏差表记） |
| **C-4.3-13 年度复审（行 1836）** | **部分降级**：台账+超期压缩+恢复落地；「禁止新增 SO」Non-Goal（销售域未开发，偏差记） |
| FR-4.3-2-x SO 信用检查/冻结/预收款 | **Non-Goal**（销售域，公式口径页面明示+桩） |
| BR-4.3-16 连续逾期自动下调 50%（行 1728） | **Non-Goal**（依赖付款数据，销售域） |
| 应收/未清 SO 占用计算（行 1571） | **Non-Goal**（桩，页面展示公式与口径） |

**已沿基线**：无审批、版本快照记变更后值、乐观锁 verNo、写接口 ROLE_ADMIN、`GROUP_CREDIT_LIMIT_RATIO` 默认 100%。

## Capabilities

### New Capabilities
- `credit-limit-management`: 信用额度管理 —— 法人常规/临时额度调整、到期回滚、集团基准调整、占用率提示、快照时间轴、年度复审台账与超期压缩

### Modified Capabilities
<!-- 无：openspec/specs/ 为空（前十二变更未归档）；customer-admission 的 delta 未合入，求和校验由本能力复用 -->

## Impact

- **后端**：迁移 021（3 列）；`MdmCustomerView` 实体加 3 字段；新 `MdmCreditService/Impl`（或并入 `MdmCustomerService`，见 design）+ `MdmCreditScheduler`（@Scheduled 回滚与复审压缩）+ Controller 端点（额度调整/集团调整/复审/到期扫描）；`SecurityConfig`
- **前端**：`api/mdm/credit-limit.js`、`views/mdm/credit-limit/index.vue`（列表+占用率+调整弹窗+时间轴抽屉+复审操作）、`router/index.js` `/m/1.3.2`
- **数据**：021 加 3 列（`erp_mdm_customer_view`），无新表；客户/物料域存量不动
- **依赖**：复用 customer-admission 的求和校验、版本快照、`GROUP_CREDIT_LIMIT_RATIO` 配置；销售/财务域引用维持 TODO 桩
