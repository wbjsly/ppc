# Proposal

## Why

法人主体新建/变更表单中的「关联成本中心」「关联利润中心」当前是**手输文本框**（单值、无校验、与中心模块数据脱节），详情也只显示存量列里的单个编码。实际业务中一个主体名下可挂**多个**成本中心与利润中心（数据源在中心侧：中心设置 `legal_entity_id`），单值手输既显示不全，也无法随中心侧变动自动同步。经需求沟通选定**方案 A（2026-10-01）**：数据方向为中心 → 主体，主体侧实时反查、只读展示，不做写回存储。

## What Changes

- **前端** `views/mdm/legal-entity/index.vue`：
  - 变更表单两项改为**只读标签组**，打开时按主体 id 调 `cost-centers/options?legalEntityId=`、`profit-centers/options?legalEntityId=` 全量反查，多个全部显示
  - 新建表单两项改为提示文案（新建时主体尚不存在，反查必然为空）：「暂无关联 —— 请先在成本中心/利润中心模块创建并挂载本主体」
  - 详情抽屉两项由单值展示改为同样的反查标签组
- **后端** `MdmLegalEntityServiceImpl`：`costCenterCode`、`profitCenterCode` 移出 `SNAPSHOT_FIELDS`、`buildDiff`、`readField` —— 表单不再提交两字段后，否则每次变更 diff 会误报 `字段: 旧值 → ` 假差异
- **存量两列退役（不删）**：`COST_CENTER_CODE`/`PROFIT_CENTER_ID` 列与实体字段保留但**读写全停**；不加 `DROP COLUMN` 迁移（破坏性操作，待用户另行确认）

### 与规格的关系

无偏差、无触碰：主体侧这两个反向关联字段本就**零规格支撑**（00-erp-spec.md 无任何条款），本改动是把「手输单值」修正为「派生多值展示」，不改变任何已有 Requirement。4.6 的「成本中心与利润中心映射关系已配置」前置由中心侧 `legal_entity_id` 归属满足，与主体侧展示无关。

**方案 B（写回多值存储）已评估并否决**：需改列 + 中心侧 5 个写路径同步 + 停用摘除，双写一致性风险高，且其唯一收益（主体数据自包含）当前无任何消费方。

## Capabilities

### New Capabilities
- `legal-entity-center-association`: 法人主体关联成本中心/利润中心的派生展示 —— 实时反查两模块全量标签、新建时的空态提示、变更/详情视图一致性

### Modified Capabilities
<!-- 无：openspec/specs/ 当前为空（前三变更未归档）；本改动不触碰既有 Requirement -->

## Impact

- Affected specs: `legal-entity-center-association`（新增）
- Affected code:
  - 前端 `frontend/src/views/mdm/legal-entity/index.vue`（表单两处 + 详情一处）
  - 后端 `backend/src/main/java/com/erp/service/impl/mdm/MdmLegalEntityServiceImpl.java`（`SNAPSHOT_FIELDS`/`buildDiff`/`readField` 移除两字段）
- 不改：接口签名、`SecurityConfig`、迁移、成本中心/利润中心两模块、实体字段与表列
- 复用：`cost-centers/options`、`profit-centers/options`（按主体过滤、仅启用、支持全部显示）
