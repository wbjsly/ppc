# Design

## Context

复用密集型变更：成本中心（3 级树 + 编码流水 + 版本快照）与利润中心（平铺选项接口）的已验证实现几乎全部适用。动机、偏差与 Non-Goals 见 proposal.md。约束同前：JDK 17 + Spring Boot 2.7.18 + MyBatis-Plus，表名 `erp_mdm_` 前缀，写接口 ROLE_ADMIN，迁移走 `DbBootstrap`。

## Goals / Non-Goals

- Goals：组织单元 3 级树 CRUD + 六类类型 + 版本快照 + C-0-08 选项接口
- Non-Goals：S-4.1-08 物料本厂视图、5.6 行级权限实现、类型-层级绑定规则、审批/生效日期/合并（基线口径）

## Decisions

**1. 直接以 `MdmCostCenter` 模块为蓝本复制改造**，仅三处实质差异：
- 编码类型码换成六类枚举映射（`TYPE_CODES` 6 项），`selectMaxCodeByType` 按类型取流水（`OU-` 前缀长 `3+类型码+1`，注意成本中心那次 `substring` 偏移坑：前缀长度须按 `3 + typeCode.length() + 1` 计算）
- 无 `IS_DEFAULT`（规格无「默认组织单元」概念，FR-4.6-5-4 的默认中心属成本中心）
- 无 `profitCenterId` 归属字段（组织单元与利润中心无规格关联）
- 备选：抽象公共树服务复用三份 → 拒绝，三个模块字段不同（类型/默认/归属各异），过早抽象成本高于复制。

**2. 类型不锁层级**（用户未异议的推定）：`COST_TYPE` 式自由枚举字段，父可 FACTORY 子可 WAREHOUSE；编码前缀与自身类型绑定、变更类型时校验前缀一致（「改类型不刷编码」+「编码不可改」二者交集 = 类型变更实际不可行，但校验信息明确优于静默）。
- 备选：类型-层级映射表（1 级仅工厂）→ 拒绝，规格无此要求，六类在 3 级树中自由组合更贴近「工厂→仓库」业务现实。

**3. 表 `erp_mdm_org_unit`**：`OU_CODE` 唯一索引、`LEGAL_ENTITY_ID`/`STATUS`/`PARENT_ID` 索引、`OU_TYPE`、`TREE_LEVEL`、BaseEntity 公共字段；版本表复用 `ENTITY_ID + VERSION_NO` 唯一模式。种子：`OU-FACTORY-01`（1 级，挂 `le-0001`）。

**4. 选项接口返回 `{id, code, name, level, type}`**：比成本中心多 `type`（下游按类型区分工厂/仓库场景，如行 721 工厂场景）。

**5. C-0-08 引用校验桩**：`queryDownstreamReferences` TODO 模式（交易单据域未建），同前两轮。

## Risks / Trade-offs

- 六类枚举硬编码 → 新增类型需改码表；沿成本中心先例本期接受，未来随字典化统一改造
- 编码前缀校验使「变更类型」必然被拒（除非类型与原码一致）→ 表单上类型字段在编辑态**禁用**（同成本中心 costType 处理），校验作 API 层兜底
- 三个树形模块（成本中心/组织单元）结构相似但独立维护 → 接受重复，等第 3 个树形需求再评估抽象

## Migration Plan

`014-mdm-org-unit.sql` 只增不改，回滚即删表；marker 顺序执行无依赖。

## Open Questions

- 无
