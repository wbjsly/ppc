# Design

## Context

- 025 已提供 `erp_mdm_exchange_rate`（区间主表）与 `erp_mdm_exchange_rate_version`（快照表：ENTITY_ID + VERSION_NO 唯一、SNAPSHOT_JSON、DIFF_SUMMARY、OP_TYPE、CHANGE_REASON），1.5.1 写路径已 `saveSnapshot`，022 `erp_ops_outbox` 已在发 `MDM.RATE.CREATED/UPDATED`（幂等键 `BASE/QUOTE:type:生效日期:vN`）。
- 已有可复用只读能力：`GET /{id}/versions`、`GET /{id}/diff`（compareSnapshots 返回 `{from,to,fields:[{field,from,to}]}`，前端 API 函数已备但无页面消费）、1.5.1 lifecycle 计算态与列表页、`MdmCrossDomainController /outbox` 分页查询。
- 缺口：跨记录全局时间线、区间链一键查询、两版本对比 UI、预填入口、`/m/1.5.3` 页面。快照 SNAPSHOT_FIELDS 含 baseCcy/quoteCcy/rateType/effectiveDate/expireDate/rate/sourceFileNo。
- 约束：读接口仅需认证；无新表无迁移；append-only 区间模型（BR-4.1-17）禁止绕过校验的真回滚。

## Goals / Non-Goals

**Goals:**
- 两个只读接口（区间链、全局时间线）+ 事件列回显 + 对比视图 + 预填跳转，全部落在既有表与既有 diff 能力上
- 时间线对软删/失效记录的历史不缺失（从快照取标识而非依赖主表 join）

**Non-Goals:**
- 不做整库时点回放（验收要点由 trial + 版本对比共同满足）
- 不新增 outbox 事件、不改 1.5.1 写路径与校验逻辑
- 不做 Excel 导出（1.5.2 的 CSV 报告机制不复用，历史页首版不提供导出）

## Decisions

### D1 区间链：Service 内按序列直查，复用 lifecycle 计算
`sequence(base, quote, type)` → `rateDao.selectSequence(base, quote, type, null)`（025 已有 IDX_MER_SEQ 复合索引，含软删过滤由 @TableLogic 处理），服务端计算 lifecycle 后整体返回（单序列段数有限，不分页）。
备选：复用 `page` 接口传 keyword —— keyword 是 like 模糊匹配会跨序列串数据，且不返回全量链，弃。

### D2 全局时间线：版本表直查 + 快照内解析记录标识
`history(current, size, rateType, opType, keyword)` 查 `erp_mdm_exchange_rate_version` 按 CREATE_DATE 倒序分页；**不 join 主表**——记录标识（币对/类型/生效区间）从 SNAPSHOT_JSON 解析（复用既有 `parse()`），保证软删记录历史不缺失；rateType 筛选因版本表无该列，首版在 SQL 层无法过滤 → 用「分页内过滤」不成立，改为：快照 JSON 无法走索引，故 rateType/opType 筛选降级为**查询条件拼 SNAPSHOT_JSON LIKE**（`%"rateType":"MIDDLE"%`）+ 关键字 `DIFF_SUMMARY/CHANGE_REASON LIKE`，量级小（单表快照），接受全表扫描。
备选 A：版本表加 RATE_TYPE 冗余列（需迁移 026）——数据可从快照重建但引入迁移，本域快照量小，弃。
备选 B：只支持关键字筛选——规格要求按类型/操作筛选，弃。

### D3 事件列：前端并查 outbox，不入 SQL join
时间线页拿到当前页结果后，按行重建幂等键（快照 baseCcy/quoteCcy/rateType/effectiveDate + versionNo），调既有 `GET /api/mdm/outbox?eventType=MDM.RATE.CREATED,MDM.RATE.UPDATED`（或按 keyword=幂等键逐批）本地匹配；后端在 history 响应中直接附 `events` 字段更简单——**选后者**：Service 分页后收集键列表，`outboxDao` 按 IDEMPOTENCY_KEY IN (...) 批查，映射进每行 `event:{eventType,idempotencyKey,status}`，无命中置 null。一次请求完成，避免前端 N+1。
备选：前端多次调 /outbox —— N+1 且跨域耦合展示层，弃。
注意：无匹配显示「—」，批查失败（表不存在等）降级 null 不报错。

### D4 对比视图纯前端，复用既有 diff
时间线行内 from/to 下拉（取该行 entityId 的 versions 列表，调既有 `GET /{id}/versions`），确认后调 `GET /{id}/diff?from&to`，抽屉逐字段并排红/绿高亮。同版本禁用在前端守卫。
备选：新增批量 diff 接口——无必要，单记录对比已够用。

### D5 预填跳转用路由 query，1.5.1 单点改造
历史页按钮 → `router.push('/m/1.5.1?prefill=...')`，prefill 为 baseCcy/quoteCcy/rateType/rate/sourceFileNo 的 query 拼接（不带生效/失效日期）；1.5.1 `onMounted` 检测 `route.query.prefill` 解析后 `openCreate()` 预填并清空路由 query（避免刷新重复预填）。无 query 时行为不变。
备选：Pinia 临时 store——多一处状态且刷新丢失语义不清，query 可分享可刷新，弃。
日期不预填：历史生效区间不可复制到当期（会违反 BR-4.1-17 或伪造历史），由用户重新选择。

### D6 权限与路由
history/sequence 接口只读（无 @PostMapping/@PutMapping），走既有 JWT 认证过滤器（401）；不加 ADMIN 注解（写在 1.5.1 已有）。路由 `/m/1.5.3` 指向新组件，与兄弟路由同级。

## Risks / Trade-offs

- [SNAPSHOT_JSON LIKE 筛选无索引、随快照增长变慢] → 快照仅汇率域写入，量级 = 记录数 × 变更次数，万级内可接受；后续可加冗余列迁移优化（非本变更）
- [幂等键重建依赖 SNAPSHOT_FIELDS 字段名稳定] → 字段名与 OutboxPublisher 发布时一致，已有 spec 固化；若未来字段改名，事件列显示「—」而非报错，降级安全
- [query 预填带中文/特殊字符] → `encodeURIComponent` 编码，sourceFileNo 为编号类，风险低
- [history 软删记录标识来自快照] → 快照即真相，主表删除只影响维护页；spec 已明确历史不缺失
- [1.5.1 改动引入回归] → 仅 onMounted 分支，冒烟覆盖「无参进入」场景（spec 已列）

## Migration Plan

无迁移。部署即生效：后端加接口、前端加页面/路由。回滚 = 还原前端路由与后端新增方法，无数据变更。

## Open Questions

（无——筛选维度、事件列、预填口径均已在需求沟通中确认）
