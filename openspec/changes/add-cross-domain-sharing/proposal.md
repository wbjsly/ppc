# Proposal

## Why

菜单 1.3.3「跨域共享」指向占位页。规格中无同名章节，语义支撑来自三处，且前两处是**明文缺口**：① FR-4.1-6-2（行 669）法人扩展视图字段含**价格协议**——至今未建（准入提案记 Non-Goal 后 1.3.2 亦未承接）；② 7.2 事件契约规范（行 8993-9012）要求跨域事件统一信封 + Outbox 至少一次投递——客户域 5 个 `MDM.CUSTOMER.*` 事件全部是 `log.info` 桩，无台账、无页面可见性。本轮补齐事件 Outbox 台账与价格协议实体（含试算），打通客户主数据的跨域共享出口。

## What Changes

### 功能一：跨域事件 Outbox 台账（7.2 事件契约 + DC-19）

- **迁移 `022-cross-domain-sharing.sql`**：
  - `erp_ops_outbox`（通用事件表，`ops` 域前缀，本期客户域写入，后续物料/信用等域复用）：`EVENT_ID` uuid、`EVENT_TYPE`、`VERSION`、`RECORD_VERSION`、`OCCURRED_AT`、`SOURCE`、`IDEMPOTENCY_KEY`（唯一，如 `CUST-0001:v3`）、`LEGAL_ENTITY_ID`、`PAYLOAD` json、`STATUS`（PENDING/DELIVERED/DEAD）、`CONSUMER_NOTE`
  - `erp_mdm_price_agreement` + `erp_mdm_price_agreement_line`（见下）
- **客户域全部写操作点接入 outbox**（建档/变更/额度调整/停用/冻结/合并/复审/回滚）：替换现有 log 桩为「outbox 插入 + 日志双写」；`IDEMPOTENCY_KEY = 业务编码:vN` 保证重放幂等（C-0-06）
- **事件目录**：页面静态登记（生产方 MDM、消费方 销售/财务/CRM 桩、Schema 版本、幂等键规则）
- **事件流页面**：按 `EVENT_TYPE`/状态/关键字筛选 + payload 详情；投递状态明示「消息总线未接入，PENDING 为预期态」（桩口径，不伪装投递成功）

### 功能二：价格协议实体（FR-4.1-6-2 缺口 + FR-4.3-1-4 载体 + FR-4.3-4-3 试算）

- **头表 `erp_mdm_price_agreement`**：`PA_CODE`（`PA-NNNN` 自动编码，创建后不可改）、名称、`AGREEMENT_TYPE`（`EXCLUSIVE` 客户专属价 / `LADDER` 量价阶梯 / `TIME` 时间价）、挂靠二选一（`CUSTOMER_GROUP_ID` 集团级 或 `CUSTOMER_VIEW_ID` 法人级，FR-4.1-6-2 两级共享）、`EFFECTIVE_DATE`/`EXPIRE_DATE`、状态机（`0` 未生效 / `1` 生效中 / `2` 过期 / `3` 停用）、变更原因、`VER_NO` 乐观锁 + 版本快照（`erp_mdm_price_agreement_version`）
- **行表 `erp_mdm_price_agreement_line`**：`PA_ID`、`ITEM_CODE`（SKU 参照物料启用项）、单价、`MIN_QTY`/`MAX_QTY`（LADDER 阶梯区间，非阶梯类型为空）、同协议同 SKU 阶梯区间不重叠校验
- **状态机**：创建即按生效日期判定 未生效/生效中；过期由**懒判定**（查询时 `EXPIRE_DATE < 今天` 视为过期并回写）+ 页面打开兜底；停用手工操作（原因必填）
- **试算接口**（FR-4.3-4-3 优先级子集）：入参 客户（集团或法人视图）+ SKU + 数量 + 日期 → 在「生效中且日期区间覆盖」的协议内按优先级 **EXCLUSIVE > LADDER > TIME** 取最优；LADDER 再按 `MIN_QTY ≤ qty ≤ MAX_QTY` 匹配行；返回适用协议（PA_CODE/类型/单价/挂靠级）或「无适用协议」明示（SO 价格阻断留销售域）
- **前端** `views/mdm/cross-domain-sharing/`：Tab 一「事件流」+ Tab 二「价格协议」（列表/新建编辑弹窗含行编辑/试算面板）；`router/index.js` 注册 `/m/1.3.3`；`SecurityConfig` 写接口 ADMIN

### 与规格的关系

| 规格条目 | 处理 |
|---|---|
| **FR-4.1-6-2 价格协议字段（行 669）** | **补齐**（升级为协议实体，准入 Non-Goal 的正式承接） |
| **7.2 事件契约信封（行 8993-9012）** | **补齐**（信封全字段入 outbox 表；事件目录页面登记） |
| **Outbox 至少一次 + 幂等键（行 203/7809、C-0-06）** | **补齐**（IDEMPOTENCY_KEY 唯一索引；消费端桩明示） |
| FR-4.3-1-4 价格计算三类协议（行 1404） | **数据载体 + 试算**；价格异常 ±30% 标记审批留销售域（偏差记） |
| FR-4.3-4-3 优先级 客专属>量价>渠道（行 1466） | **部分**：EXCLUSIVE>LADDER>TIME；**渠道基准价（CHANNEL 第四类）Non-Goal**（销售域另立，偏差记） |
| DC-19 Outbox 对账（行 8703） | **降级**：台账可查可数；自动日对账 Non-Goal（无消费端，偏差记） |
| SO 绑定价格协议、无匹配阻断（行 1466/1404） | **Non-Goal**（销售域）；试算接口为其预铺 |
| 事件真实投递（消息总线/RabbitMQ） | **Non-Goal**（平台目标态）；STATUS 恒 PENDING 桩口径 |

**已沿基线**：无审批、立即生效、版本快照记变更后值、乐观锁 verNo、禁硬删、编码创建后不可改、写接口 ROLE_ADMIN。

## Capabilities

### New Capabilities
- `cross-domain-sharing`: 跨域共享 —— 客户事件 Outbox 台账（统一信封/幂等键/事件目录/事件流视图）与价格协议实体（头+行三类、两级挂靠、状态机与懒过期、试算优先级接口）

### Modified Capabilities
<!-- 无：openspec/specs/ 为空（前十三变更未归档）；customer-admission delta 未合入，价格协议为其字段缺口的实体化承接 -->

## Impact

- **后端**：迁移 022（3 表 + outbox）；新实体 `MdmOutboxEvent`/`MdmPriceAgreement(+Line,+Version)` + DAO；`MdmCrossDomainService/Impl`（outbox 写入 + 试算 + 懒过期）；`MdmCustomerServiceImpl`/`MdmCreditServiceImpl` 各写操作点接入 outbox（替换 log 桩）；`MdmCrossDomainController`（`/api/mdm/outbox`、`/api/mdm/price-agreements`）；`SecurityConfig`
- **前端**：`api/mdm/cross-domain-sharing.js`、`views/mdm/cross-domain-sharing/index.vue`（双 Tab）、`router/index.js` `/m/1.3.3`
- **数据**：4 张新表（outbox/agreement/line/version）；存量客户表不动
- **依赖**：物料域（SKU 参照）、客户准入/信用额度（挂靠对象与事件源）；消费端（销售/财务）维持桩
