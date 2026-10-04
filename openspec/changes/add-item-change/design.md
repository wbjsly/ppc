# Design

## Context

增量变更：`add-item-master` 已交付基础变更（update/乐观锁/V(N+1) 快照/编码阻断），本轮补齐流程二剩余语义。动机与偏差见 proposal.md。约束同前：JDK 17 + Spring Boot 2.7.18、`erp_mdm_` 表前缀、`DbBootstrap` 迁移、版本快照记变更后新值、写接口 ROLE_ADMIN。

## Goals / Non-Goals

- Goals：变更原因/分类落库审计、差异预览、分类警示、编码跳转向导、1.2.2 入口页
- Non-Goals：审批状态机、生效日期、事件发布实现、BOM 影响评估（均见 proposal 偏差表）

## Decisions

**1. 版本表加两列而非另建变更单表**：`CHANGE_REASON VARCHAR(255)` + `CHANGE_TYPE VARCHAR(16)`（`CRITICAL`/`GENERAL`/`NULL`-for-CREATE）。
- 版本记录天然就是变更流水（谁/何时/改了什么 + diffSummary），再建申请单表是重复模型；无审批状态下「变更单」没有独立生命周期。
- 备选：独立 `erp_mdm_item_change_request` 表 → 拒绝，审批落地时若需要工作流状态再演进（届时版本表已含全部审计要素）。
- ALTER 用 `ADD COLUMN`（marker 保证单次执行，同 013 先例：**不用** MySQL 不支持的 `IF NOT EXISTS`）。

**2. `changeType` 服务端计算**：`update` 内取 `buildDiff` 的字段名集合与关键清单 {baseUnit, categoryCode, purchaseType} 求交 → 有交集则 CRITICAL。客户端警示只是同规则的前端镜像（易用性），落库判定以服务端为准（防 API 直传绕过）。

**3. 差异预览放前端**：变更弹窗内维护「表单值 vs 打开时快照」逐字段比对，与 `buildDiff` 同字段集；提交前展示，空差异前端直接拦截（服务端 `diffSummary` 为空也可双保险返回 422 —— 决定：**服务端也拦**，无差异提交返回 422「无变更内容」，保持口径一致）。

**4. 跳转向导 = 打开既有新建弹窗 + 预填**：阻断错误码 422 + message 带标记；前端捕获后 `openForm(null, prefillFromItem)` —— 预填字段白名单：itemName/categoryCode/baseUnit/materialGroup/purchaseType/storageCondition/batchFlag/shelfLifeDays/packingSpec/barcode，**编码不预填**（自动生成），`altItemCode` 可预填原编码（停用旧编码的衔接线索）。复用同一弹窗，不新建向导组件。

**5. 页面复用**：`views/mdm/item/index.vue` 增加 `route.meta.mode: 'change'` 区分 —— 1.2.2 隐藏「新建」按钮、列表操作列突出「发起变更」（1.2.1 保留现有变更按钮，两页共用组件与筛选/详情/停用能力）。
- 备选：复制一份 item-change 页面 → 拒绝，双份维护。

**6. 接口签名**：`PUT /api/mdm/items` body 增加 `changeReason` 字段（MdmItemVersion 的 reason 不来自 item 实体 —— update 入参用 `MdmItem` + 新增 transient `changeReason` 字段，或单独包 DTO）。决定：**MdmItem 加 `@TableField(exist=false) changeReason`**（与 `bomPending` 同模式），避免新 DTO 破坏既有调用。

## Risks / Trade-offs

- 无审批使 CRITICAL 变更可被单人执行 → 警示文案明示「后续纳入双人复核」，偏差已在 proposal 记录，风险由用户接受
- 版本表现有历史行两新列为 NULL → diff/展示层对 NULL 兼容（reason 空显示 `-`，type 空显示 `-`）
- 预填弹窗字段多，白名单遗漏某字段无硬后果（新建本就允许后补）→ 白名单在 design 固定，实施按单核对

## Migration Plan

`017-item-change-audit.sql`：两条 `ADD COLUMN`（无 IF NOT EXISTS），幂等靠 marker；回滚 `DROP COLUMN`。加列对存量行为零影响。

## Open Questions

- 无
