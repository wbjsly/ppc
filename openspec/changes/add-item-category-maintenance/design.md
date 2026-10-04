# Design

## Context

分类表 `erp_mdm_item_category` 与只读接口已由 `add-item-master` 落地（5 种子行、编码规则权威），本轮补管理能力。动机与偏差（流程四整体 Non-Goal）见 proposal.md。约束同前：JDK 17 + Spring Boot 2.7.18 + MyBatis-Plus、`erp_mdm_` 表前缀、`DbBootstrap` 迁移、写接口 ROLE_ADMIN、基线（无审批/立即生效/版本快照/乐观锁）。

## Goals / Non-Goals

- Goals：分类树 CRUD/停用、规则字段锁定、双重阻断、受限合并（含物料批量改挂）与迁移、分类版本审计
- Non-Goals：流程四全套（影子树/方案单/Excel 导入/影响分析报告/试运行/窗口调度/Retired 态）、动态属性集（见 proposal 偏差表）

## Decisions

**1. 合并的物料改挂实现为「单事务内批量 UPDATE + 循环逐条快照」**：`erp_mdm_item` 按 `CATEGORY_CODE = 源` 全量改挂目标，每条物料写一条版本快照（`OP_TYPE=UPDATE`、`diffSummary=categoryCode: X → Y（分类合并）`、快照为改挂后状态，沿「快照记该版本状态」口径）。
- 规模边界：分类下物料数在万级以内事务可承受（当前系统数据量级）；超过 5000 条时改为**分批提交**（每批 500，批间快照不中断）—— 首版先单事务 + 数量上限提示（>5000 提示确认），分批执行留桩。
- 备选：物料 `CATEGORY_CODE` 不改挂、靠映射关系表解析 → 拒绝，物料表已有该列且新建校验直接读列，映射表引入双源不一致风险。

**2. 同前缀限制 = 服务端硬校验**：`源.itemPrefix != 目标.itemPrefix → 422`。这是「前缀+分类码锁定」决策的延伸 —— 物料编码里的前缀段与分类码段都不可变，跨前缀合并会让存量物料的编码前缀与新归属分类码语义断裂（BR-4.1-07 校验会拒绝这些物料的任何重新提交）。

**3. 分类版本表 `erp_mdm_item_category_version`**：结构与前七轮一致（`ENTITY_ID+VERSION_NO` 唯一、`SNAPSHOT_JSON`/`DIFF_SUMMARY`/`OP_TYPE`/`CHANGE_REASON`），快照记变更后状态；`MERGE`/`MOVE` 作为 `OP_TYPE` 新增值（区别 CREATE/UPDATE/DISABLE）。合并/迁移的物料改挂结果**不写入分类快照的 diffSummary**（物料影响面在物料自身快照里，分类快照只记自身字段与操作原因）。

**4. 双重阻断的物料引用查询**：`SELECT ITEM_CODE ... WHERE CATEGORY_CODE = ? AND STATUS='1' LIMIT 10` + 总数单独 count（提示用「及共 N 条」）；复用 `queryDownstreamReferences` 的桩风格但此处是**真实查询**（表就在本域）。

**5. 页面形态**：`views/mdm/item-category/index.vue` 树形表格（编码/名称/前缀/层级/状态）+ 操作列（详情/变更/停用/**合并**/**迁移**）；合并与迁移用独立小弹窗（合并含目标选择器仅同前缀+影响提示+原因必填；迁移含父节点选择器+原因必填）；新建/变更复用表单（前缀与编码字段编辑态禁用）。
- 只读接口 `/item-categories` 保持现有返回（物料表单消费方不破坏），管理页树形数据走新增 `GET /api/mdm/item-categories/tree`。

**6. 物料批量改挂的分类判定涟漪**：物料 `CATEGORY_CODE` 变化后，其后续变更的 `changeType` 判定基于新分类 —— 预期内（proposal 已记）；无需额外处理。

## Risks / Trade-offs

- 合并物料数 >5000 时长事务锁 `erp_mdm_item` → 首版接受（提示确认 + 数据量级现实），分批桩已标
- 分类停用后历史物料仍指向它 → 停用仅挡新引用（物料新建时应校验分类状态 = 启用，**本轮补此校验**：`requireCategory` 已查存在性，扩展为查启用状态，否则停用分类可被新物料引用 → 不一致）
- 迁移改变层级但不改编码 → 与流程四「按新分类前缀校验新申请编码」无冲突（前缀未变）

## Migration Plan

`018-mdm-item-category-maintenance.sql`：仅新建版本表，不改分类表结构；只增不改，回滚删表。

## Open Questions

- 无
