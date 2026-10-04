# Design

## Context

跨模块改动：新建利润中心模块 + 改造既有成本中心（加归属字段与校验）+ 两组迁移。动机见 proposal.md - Why。约束同前两轮：JDK 17 + Spring Boot 2.7.18 + MyBatis-Plus，无工作流引擎，表名 `erp_域_` 规范，迁移用 `DbBootstrap`，写接口 ROLE_ADMIN。规格对利润中心仅 3 处引用（453/2914/2945），平铺形态与 `PC-0001` 编码是需求沟通的推导决策。

## Goals / Non-Goals

- Goals：利润中心平铺 CRUD + 版本快照；成本中心 `profit_center_id` 归属的写入与三校验；满足 4.6 前置「成本中心与利润中心映射关系已配置」
- Non-Goals：利润中心层级/树形、默认利润中心概念、分摊比例与多对多映射、凭证行真实引用校验（待 4.6）

## Decisions

**1. 归属用成本中心表内字段 `profit_center_id`，非独立映射表**（用户 2026-10-01 确认）。
- 一个成本中心只属一个利润中心，字段即可；映射表为多对多/分摊比例而建，当前无需求支撑。
- 可空：允许成本中心暂不归属（规格「如适用」精神），不强制 100% 归属。
- 备选：映射表 → 拒绝，YAGNI，且增加一次 join 与写路径复杂度。

**2. 迁移拆两个文件**：`012-mdm-profit-center.sql`（建表 + 种子 `PC-0001`）、`013-cost-center-profit-center.sql`（`ALTER TABLE ADD COLUMN` + 存量 `CC-PROD-01` 回填 `PC-0001`）。
- 拆开的原因：013 依赖 012 的种子行，按编号顺序执行保证回填目标存在；加列用 `IF NOT EXISTS`（MySQL 8 支持 `ADD COLUMN IF NOT EXISTS`）保证幂等。
- 备选：合一个文件 → 拒绝，`ALTER` 与建表混在一起在回滚时不好单独处理。

**3. 同主体校验位置在 Service 写路径**（创建/变更成本中心、变更归属时），用「查利润中心 → 比对 `legalEntityId`」单点查询；**主体变更联动**：成本中心改主体时若原利润中心不属于新主体，直接**清空** `profitCenterId`（前端提示已重置），比阻断更少摩擦且不会留下脏关系。
- 备选：阻断要求先手工调利润中心 → 拒绝，改主体本身低频，清空是安全默认（归属可空）。

**4. 停用阻断用「查归属它的启用成本中心」**：`profit_center_id = ? AND status = '1'`，非空即阻断并列出编码/名称。复用成本中心停用的阻断交互（错误码 409 + 清单文案）。
- 下游凭证行引用校验沿用 `queryDownstreamReferences` 桩模式（TODO 标注，4.6 落地回补）。

**5. 版本快照复用既有模式**：`erp_mdm_profit_center_version`，快照记该版本状态（变更后新值），diff 逐字段；成本中心侧因加列产生的历史数据无 `profitCenterId` 字段，diff 对缺失字段按空串处理（`compareSnapshots` 已兼容 `has()` 判断）。
- 成本中心的归属变更也计入其版本快照 diff（`buildDiff` 追加 `profitCenterId` 一项）。

**6. 编码生成**：`PC-` + 4 位流水，`MAX(CC_CODE)` 同款逻辑（`substring(3)`，无类型码段所以无成本中心那次的偏移坑），唯一索引冲突重试 3 次。跨主体全局唯一，避免下游引用歧义。

## Risks / Trade-offs

- `ALTER TABLE` 在成本中心表有数据时锁表 → 当前仅 1 行种子，风险可忽略；生产化后走在线 DDL 评估
- 改主体清空归属可能让用户困惑 → 前端提交成功后提示「原利润中心归属已因主体变更重置」
- `PC-0001` 种子与真实业务混杂 → 同前两轮，种子标注示例，投产前替换
- 成本中心版本快照新增字段导致旧快照 diff 缺项 → `compareSnapshots` 对缺失字段返回空串，不报错

## Migration Plan

012 → 013 顺序执行，marker 记录；回滚 = 删两表 + `ALTER DROP COLUMN`（不涉及数据删除，仅丢归属列）。加列默认 `NULL`（可空），不回填的存量行归属为空属预期。

## Open Questions

- 无（分摊比例、多对多、利润中心层级均明确为 Non-Goal，未来单独变更）
