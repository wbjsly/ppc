# Proposal

## Why

菜单 1.2.5「替代物料」指向占位页。替代关系是「物料停用/替代场景」（行 554 / S-4.1-06：供应商停产或产品升级 → 停用并指定替代）的载体，且规格行 477 明文要求**「替代物料编码是否已发布」须做参照完整性校验 —— 该 gap 自 `add-item-master` 起从未实现**（`altItemCode` 至今是无校验的自由文本）。本轮补齐明文 gap，并为替代关系提供集中视图与维护入口。

## What Changes

- **迁移 `019-substitute-material.sql`**：`erp_mdm_item.ALT_ITEM_CODE` 加索引（反查 `WHERE ALT_ITEM_CODE = ?`），无列变更
- **替代关系列表页**（1.2.5）：所有配置了替代的物料 —— 正向「A 的替代是 B」+ 反查「谁指向我」筛选 + 分页
- **关系维护**：列表内「设置替代 / 清除替代」，与物料表单 `altItemCode` 同字段双向同步，即时生效并计入该物料版本快照（`OP_TYPE=UPDATE` + diff 记 `altItemCode` 变化）——**不另建关系表**
- **校验组**（设置替代与物料表单提交共用）：
  - 替代目标**已发布（启用中）**（行 477 明文 gap 补齐）
  - **非自身**（A→A 阻断）
  - **间接环阻断**：回溯目标物料替代链（深度上限 10），出现源物料 → L1 阻断并展示链路（`B → C → A`）
- **替代目标停用阻断**：停用物料 X 若被其他物料指向 → 阻断并列出引用方（`LIMIT 10` + 总数）
- **物料表单补校验**：`add-item-master` 的 `altItemCode` 输入补上「已发布 + 非自身 + 环」三校验
- **停用提示替代（非强制）**：物料停用确认框展示当前替代指向；无替代时给非强制建议文案
- 前端 `views/mdm/substitute/`，菜单 `M1-2-5`（`/m/1.2.5`）切真实页；`SecurityConfig` 写接口 ADMIN

### 与规格的关系（本变更是「补 gap + 规则补强」型）

| 规格条目 | 处理 |
|---|---|
| **行 477 参照完整性（已发布）** | **补齐**（明文 gap，本轮核心） |
| 行 554 / S-4.1-06 停用并指定替代 | 部分对齐：停用时展示/提示替代（非强制 —— 场景描述非 BR，未升级为阻断） |
| 行 467 替代编码选填 | 已有，保持 |
| **间接环阻断（深度 10 + 链路展示）** | **规格空白处补强**（A→B→C→A 死循环语义崩塌），偏差表记「规格未覆盖，已补规则」 |
| **替代目标停用阻断** | **规格空白处补强**（引用完整性闭环，DC-04 精神），偏差表同上 |
| 链式深度限制 / 一对多替代 | **Non-Goal**（规格无依据，字段单值即单链） |

**已沿基线**：无审批、立即生效、版本快照、乐观锁、禁硬删、写接口 ROLE_ADMIN。

## Capabilities

### New Capabilities
- `substitute-material-management`: 替代关系的集中视图与维护、三项校验（已发布/非自身/间接环）、替代目标停用阻断、物料表单参照 gap 补齐、停用提示

### Modified Capabilities
<!-- 无：openspec/specs/ 为空（前八变更未归档）；add-item-master 的 delta 未合入，表单校验增强并入本能力 -->

## Impact

- Affected specs: `substitute-material-management`（新增）
- Affected code:
  - 迁移 `backend/src/main/resources/db/mysql/019-substitute-material.sql`
  - 后端 `MdmItemService/Impl`（setSubstitute/clearSubstitute/substituteList、三项环校验、`disable()` 扩展引用阻断、create/update 的 altItemCode 补校验）、`MdmItemDao`（反查 SQL）、`Controller` 新端点、`SecurityConfig`
  - 前端 `frontend/src/api/mdm/substitute.js`、`views/mdm/substitute/index.vue`、`views/mdm/item/index.vue`（altItemCode 校验提示联动）、`router/index.js` 注册 `/m/1.2.5`
- 不改：物料表结构（仅加索引）、其余模块
