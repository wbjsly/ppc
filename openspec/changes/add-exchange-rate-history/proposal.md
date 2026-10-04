# Proposal

## Why

规格 FR-4.1-3-4 / BR-4.1-18 / C-4.1-06 要求汇率变更保留完整历史、支持任意两版本差异对比与历史回溯，界面要素明确包含「版本对比视图」；而 1.5.1 汇率维护仅在单记录内提供 versions/diff 接口（前端未消费），1.5.3 历史汇率菜单为占位页——历史只散落在维护页局部，缺少跨记录的统一历史视图。

## What Changes

- 新增 **1.5.3 历史汇率** 页面（`/m/1.5.3`，菜单 M1-5-3 已注册），双视角：
  - **区间历史链**：按 币对×汇率类型 查询完整 append-only 区间序列（含已失效），带计算态、来源文件编号、版本号标签
  - **版本快照时间线**：全局跨记录的版本流水分页（实体、版本号、CREATE/UPDATE、差异摘要、变更原因、操作人、时间）
- 新增后端接口：
  - `GET /api/mdm/exchange-rates/sequence?baseCcy&quoteCcy&rateType` 区间链查询
  - `GET /api/mdm/exchange-rates/history?current&size&...` 全局版本时间线（跨 `erp_mdm_exchange_rate_version`）
- **版本对比视图**：时间线任选两版本（from/to），复用已有 `GET /{id}/diff`，前端逐字段并排高亮（旧值红/新值绿）
- **回滚降级为预填**：版本行「按此历史值发起维护」按钮 → 跳转 1.5.1 新建表单预填（币对/类型/汇率/来源编号），BR-4.1-17 区间衔接校验照常执行；历史页本身只读（不做真回滚——append-only 区间模型下回滚破坏区间链，基线偏差记录）
- **事件联动**：时间线回显 `MDM.RATE.CREATED/UPDATED` outbox 事件列（事件类型、幂等键、投递状态），由快照字段重建幂等键 `BASE/QUOTE:type:生效日期:vN` 批量查询 `erp_ops_outbox`，无匹配显示「—」
- 1.5.1 页面支持接收路由 query 预填新建表单（仅此一处改动）
- 无数据库迁移（025 已提供 rate + version 表）

## Capabilities

### New Capabilities

- `exchange-rate-history`: 汇率历史查询：区间历史链、全局版本快照时间线、两版本逐字段对比、历史值预填维护入口、时间线事件回显与只读权限口径

### Modified Capabilities

（无——1.5.1 维护页仅新增路由 query 预填入口，不改变其既有规格行为；事件与 diff 复用既有能力）

## Impact

- 后端：`MdmExchangeRateController` / `MdmExchangeRateService(Impl)` 新增 2 个只读接口；`MdmCrossDomainService.outbox` 查询复用（或 DAO 直查 `erp_ops_outbox`）
- 前端：新增 `views/mdm/exchange-rate-history/index.vue`、`api/mdm/exchange-rate.js` 补 2 个方法、`router/index.js` 增 `/m/1.5.3`；`exchange-rate/index.vue` 支持 query 预填
- 无迁移、无新表、无权限模型变化（读接口已认证即可，写仍在 1.5.1 由 ADMIN 把关）

### 偏差表（规格 vs 当前落地）

| 规格条目 | 规格要求 | 本变更口径 | 级别 |
|---|---|---|---|
| 业务逻辑2（L539） | 一键回滚至历史版本，回滚生成新版本 | 降级为「按历史值预填新建表单」，由区间规则与乐观守卫决定能否落地；不生成绕过 BR-4.1-17 的自动回滚 | L2 降级入偏差表 |
| FR-4.1-3-3 / 界面要素 | 批量导入 Excel | 1.5.2 已交付（CSV 零依赖），本变更不重复 | 已覆盖 |
| 验收要点「还原任意历史时点」 | 版本回溯 | 由 1.5.1 trial 按日期取数 + 版本时间线/对比共同满足；不提供"整库时点快照回放" | 解释性口径 |
