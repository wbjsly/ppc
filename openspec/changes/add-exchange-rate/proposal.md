# Proposal

## Why

菜单 1.5.1「汇率维护」指向占位页，**汇率域零代码零表**。规格对汇率维护的定义集中在：FR-4.1-3-1（行 530，四字段+来源编号）、C-4.1-04（行 571，生效日期与来源编号 L1 必附）、BR-4.1-16（行 750，必填缺失阻断）、BR-4.1-17/C-4.1-14（行 751/793，区间不重叠不断档）、BR-4.1-18（行 752，历史不被重算）。汇率缺失会直接阻断财务域凭证折算（FR-4.6-1-5）与期末重估（C-4.6-03）。本轮建起汇率主数据（维护 + 区间校验 + 试算查询），为财务多币种与月结提供载体。

## What Changes

### 功能一：汇率维护（FR-4.1-3-1 + C-4.1-04 + BR-4.1-16/17）

- **迁移 `025-exchange-rate.sql`**：`erp_mdm_exchange_rate`（`BASE_CCY`/`QUOTE_CCY` ISO 4217 3 位、`RATE_TYPE`（MIDDLE 中间价/BUY 买入价/SELL 卖出价）、`EFFECTIVE_DATE`/`EXPIRE_DATE` 闭区间、`RATE DECIMAL(18,6)` 6 位精度（FR-4.6-4-3）、`SOURCE_FILE_NO` 来源文件编号、变更原因、`VER_NO` 乐观锁）+ `erp_mdm_exchange_rate_version`（版本快照，`ENTITY_ID+VERSION_NO` 唯一）；`KEY (BASE_CCY, QUOTE_CCY, RATE_TYPE, EFFECTIVE_DATE)`
- **校验组**（新建/变更共用）：
  - 必填（BR-4.1-16 L1）：生效日/失效日/来源文件编号/汇率值>0；失效日 ≥ 生效日
  - 币种：ISO 4217 格式（3 位大写字母）校验，BASE ≠ QUOTE
  - **区间冲突（BR-4.1-17/C-4.1-14，按 币种对×汇率类型 独立序列）**：与既有区间相交 → 422 + 冲突区间清单；断档（新生效日 > 同序列上一条失效日 + 1 天）→ 422 + 衔接口径提示；同序列首条无历史 → 放行；变更时同区间序列排除自身
- **编辑语义（BR-4.1-18 落地）**：已失效（失效日 < 今天）→ 422 仅可查看（历史 append-only）；生效中/未生效可改（原因必填入版本快照 + 重跑冲突校验）；失效日期必填（消除了「不限期占位阻断后续插入」的歧义——全部区间显式闭合）
- **列表生命周期展示**：无独立状态列，按日期计算显示 未生效/生效中/已失效 标签（纯展示，不落库）

### 功能二：汇率试算查询（FR-4.6-1-5 预铺）

- `GET rate?base=&quote=&type=&date=` → 在目标日期落在生效区间内的记录中返回汇率；无命中 → 明示「该日期无有效汇率」+ 已排查（无记录/区间不覆盖）——财务域凭证折算「缺失或过期提示」的数据接口
- 页面试算面板：币种对 + 类型 + 日期 → 结果卡（rate + 区间 + 来源编号）或缺失明示

### 功能三：页面 `/m/1.5.1`

- `views/mdm/exchange-rate/index.vue`：列表（币对/类型/区间/计算态标签/汇率值/来源编号）+ 筛选（币对/类型/计算态）+ 分页；新建/变更弹窗（四字段 + 冲突清单回显）；试算面板 Tab 或抽屉
- 事件接入复用 022 `OutboxPublisher`：`MDM.RATE.CREATED` / `MDM.RATE.UPDATED`（幂等键 `BASE/QUOTE:type:vN`），事件流下拉 +2 类型
- `router/index.js` 注册 `/m/1.5.1`；`SecurityConfig` 写接口 ADMIN

### 与规格的关系

| 规格条目 | 处理 |
|---|---|
| **FR-4.1-3-1 四字段+来源编号（行 530）** | **补齐**（核心） |
| **C-4.1-04 生效日期+来源编号 L1（行 571）** | **补齐** |
| **BR-4.1-16 必填缺失阻断（行 750）** | **补齐** |
| **BR-4.1-17 / C-4.1-14 区间不重叠不断档（行 751/793）** | **补齐**（粒度按 币种对×汇率类型——字面「同一币种对」差异记解释性偏差，否则三类型无法并存） |
| **BR-4.1-18 历史不被重算（行 752）** | **部分**：维护侧落地为已失效区间 append-only 不可改删；业务凭证按发生日取数 = 试算接口按日期查区间（消费侧核算属财务域） |
| FR-4.6-1-5 汇率缺失提示（行 2947） | **试算接口预铺**（消费端凭证逻辑属财务域，偏差记） |
| FR-4.1-3-3 批量导入（行 532） | **Non-Goal**（属 1.5.2 批量更新菜单） |
| FR-4.1-3-4 历史保留视图（行 533） | **Non-Goal 主体**（属 1.5.3 历史汇率菜单；本期 append-only 保证其数据基础） |
| C-4.1-15 月结覆盖检查（行 794） | **Non-Goal**（财务域月结） |
| BR-4.6-24 汇率自动获取通道（行 3111） | **Non-Goal**（本期手动录入） |
| 税码/税率（FR-4.1-3-2） | **Non-Goal**（属 1.6 税码管理） |

**已沿基线**：无审批（L1 硬阻断即门槛）、快照记变更后值、乐观锁 verNo、禁硬删、写接口 ROLE_ADMIN、事件复用 022 OutboxPublisher。

## Capabilities

### New Capabilities
- `exchange-rate-maintenance`: 汇率维护 —— 币对/类型/区间/来源编号模型、必填与格式校验、币对×类型区间冲突断档阻断、历史 append-only、试算查询、版本快照与 Outbox 事件

### Modified Capabilities
<!-- 无：openspec/specs/ 为空（前十六变更未归档），汇率域为全新能力 -->

## Impact

- **后端**：迁移 025（2 表）；新实体 `MdmExchangeRate(+Version)` + DAO（区间冲突查询、试算命中查询）；`MdmExchangeRateService/Impl`（CRUD+校验组+试算）+ `MdmExchangeRateController`（`/api/mdm/exchange-rates`）；事件复用 `OutboxPublisher`；`SecurityConfig`
- **前端**：`api/mdm/exchange-rate.js`、`views/mdm/exchange-rate/index.vue`、`router/index.js` `/m/1.5.1`；cross-domain 事件流下拉 +2 类型
- **数据**：2 张新表；存量域不动
- **依赖**：022 OutboxPublisher；财务域凭证折算/月结为桩消费方
