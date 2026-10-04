## Context
这是**第一个业务域模块**（既有代码只有系统管理的 auth/menu/role）。它要确立业务域模块的分层样板，后续 17 个域按此复制。约束：JDK 17 + Spring Boot 2.7.18 + MyBatis-Plus，无工作流引擎、无消息队列、无 Redis；迁移用自研 `DbBootstrap`；表名须 `erp_mdm_` 前缀。

## Goals / Non-Goals
- Goals：法人主体 CRUD + 停用 + 版本快照；为下游提供 `legal_entity_id` 引用源；确立业务域模块代码样板
- Non-Goals：审批流、未来生效日期、主体合并、Excel 批量导入、事件总线发布

## Decisions

**1. 包结构按域分包**：`entity/mdm`、`dao/mdm`、`service/mdm`、`service/impl/mdm`、`controller`（`Mdm` 前缀）。
- 备选：平铺进现有 `system` 包 → 拒绝，`system` 语义是系统管理，且 18 个域平铺会迅速失控。

**2. 版本快照独立表 `erp_mdm_legal_entity_version`**：每次变更/停用后写入整行快照（`version_no`、`snapshot_json`、`diff_summary`），快照语义为**该版本状态（变更后新值）**，主表不承载历史。
- 关键：若存变更前旧值，则 Vn 快照 = V(n-1) 状态，相邻版本 diff 恒为空，版本对比功能失效（实施期发现并修正）。
- 备选：主表冗余历史列 → 拒绝，无法支持多版本对比。
- 备选：`snapshot_json` 一张表存 JSON → 接受，快照是整行定长结构，JSON 足够且避免为每个字段建列；对比在服务端解析后逐字段计算。

**3. 编码生成 `LE-` + 4 位流水**（如 `LE-0001`）：取当前最大编码 +1，在事务内以 `SELECT MAX ... FOR UPDATE` 思路串行化；实体上编码字段不可更新（服务层校验，非仅前端禁用）。
- 备选：UUID/雪花号 → 拒绝，规格示例为 `LE-001`~`LE-005` 且人工可读是主数据基本要求。

**4. 卡控在服务层统一执行**，不用数据库触发器：编码唯一 + 查重提示近 3 条（C-4.1-07）、编码不可改（C-4.1-01）、停用校验下游引用（C-4.1-03，本期下游无表，校验逻辑实现为查询桩 + 可扩展点）、乐观锁 `ver_no`（BaseEntity 已有）。

**5. 无审批的变更留痕**：`create_by/update_by` + 版本快照承担审计；不单独建操作日志表。

## Risks / Trade-offs
- 无审批直接生效 → 主数据误改风险；用乐观锁 + 版本快照缓解，可追溯可人工回滚（按快照重新提交）。
- 编码流水并发冲突 → 事务 + 唯一索引双保险，冲突时重试一次。
- 下游引用校验本期为空实现 → 未来第一个下游模块落地时必须回补，记入 tasks 尾项为显式技术债。

## Migration Plan
新增迁移 `010-mdm-legal-entity.sql`（`CREATE TABLE IF NOT EXISTS` + `INSERT IGNORE` 种子：`LE-0001` 示例主体），只增不改，回滚即删表，无存量数据影响。

## Open Questions
- 下游引用校验的具体表清单（待首个下游模块确定）
