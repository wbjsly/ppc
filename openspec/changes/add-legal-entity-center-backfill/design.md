# Design

## Context

动机与方案取舍见 proposal.md。现状：两字段是主体表单中的手输文本框（单值），仅被主体自身 CRUD/表单/详情消费，无任何下游；两个 options 接口（`cost-centers/options`、`profit-centers/options`）已支持按主体过滤、仅启用、全量返回。依赖方向：中心 → 主体（中心持有 `legal_entity_id`）。

## Goals / Non-Goals

- Goals：主体侧关联中心的只读多值派生展示；diff/快照不再包含退役字段
- Non-Goals：写回存储（方案 B 已否决）、反向绑定（主体表单改变中心归属）、删列、改 options 接口

## Decisions

**1. 派生读取而非写回**（方案 A，用户 2026-10-01 确认）：中心侧任何变动自动反映，零同步代码、零一致性风险；方案 B 的唯一收益（自包含存储）当前无消费方。
- 备选：写回多值列 → 否决，需中心侧 5 个写路径同步 + 停用摘除，双写漂移风险不值得。

**2. 存量两列退役不删**：`COST_CENTER_CODE`/`PROFIT_CENTER_CODE` 与实体字段保留但读写全停。
- `DROP COLUMN` 是破坏性操作且无消费方催促，留待用户单独确认；保留死列零风险。
- 备选：本变更附带 014 迁移删列 → 拒绝，超出本变更授权范围。

**3. diff/快照移除两字段而非保留**：表单不再提交后，`buildDiff` 拿「存量旧值 vs 传入 null」会每次变更误报 `costCenterCode: X → ` 假差异；`SNAPSHOT_FIELDS`/`readField` 同步移除保证快照与 diff 口径一致。
- 旧快照兼容：`compareSnapshots` 只按当前字段集迭代，历史快照中多出的两字段自然被忽略，无需迁移数据。

**4. 前端按「有无 id」分形态**：编辑（`form.id` 存在）→ 反查标签组；新建 → 空态提示文案。两项均为只读，不进提交体。
- 备选：新建也渲染 disabled 下拉 → 拒绝，空下拉易被当成 bug，提示文案更明确。

## Risks / Trade-offs

- 死列长期留存形成困惑 → proposal 已标注「待用户另行确认删除」，字段上可加注释（实施时在实体字段加 `@Deprecated` 注释性质说明）
- 主体详情每次打开多两次 options 请求 → 数据量小（中心数量级 <100），无性能顾虑

## Migration Plan

无迁移。回滚 = 还原前端三处与后端字段集，存量列一直在。

## Open Questions

- 无
