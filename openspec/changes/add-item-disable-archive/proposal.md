# Proposal

## Why

菜单 1.2.4「物料停用」指向占位页（菜单种子已有、router 无路由）。规格 FR-4.1-1-6（行 509-516）对停用定义了完整链路：**停用前影响分析 → L1 硬阻断引用清单 → 停用 → 通知单**，并附带**归档**操作；现状是物料列表页仅有一个行内停用按钮，影响分析不可见、通知无桩、归档完全缺失、误停用无法自愈。本轮把停用/启用/归档收敛为专属工作台，补齐规格明文链路。

## What Changes

- **专属页面「停用工作台」**（1.2.4，`/m/1.2.4`）：全量物料列表（含已停用/已归档）+ 状态/关键字筛选，单条与**批量停用**（逐条校验、单条失败不阻断其余）、**启用**、**归档**操作
- **影响分析**（停用确认前展示，接口明示口径）：
  - 替代引用：真实数据（`ALT_ITEM_CODE` 反查），有引用 → 409 硬阻断 + 引用方清单（复用 `add-substitute-material` 成果）
  - PO/SO/BOM 下游引用：**保留 `queryDownstreamReferences` 桩**，接口返回空清单 + 页面明示「采购/销售/生产模块未接入，暂无数据」
- **停用**：状态 `1 → 0`，必填停用原因；替代引用阻断优先；`OP_TYPE=DISABLE` 快照记 `status: 1 → 0` + 原因（现有逻辑补原因入 diff）
- **启用**（新增，规格空白处补强）：仅 `0 → 1`，必填原因；启用时重跑校验 —— 自身替代指向目标若已失效（停用/归档）→ 阻断提示先清除或改指；`OP_TYPE=ENABLE` 快照
- **归档**（标记式，非迁移）：仅「停用 + 无活跃引用」可归档，必填归档原因；状态 `0 → 2`；已归档不可被新业务引用、不可直接启用（须先由管理员解除归档回停用态 —— 本期不做解除，归档视为终态）；`OP_TYPE=ARCHIVE` 快照
- **通知单 TODO 桩**：停用成功留 `MDM.ITEM.DISABLED` 事件日志桩（推送采购/销售/库存，同 `MDM.ITEM.PUBLISHED` 处理方式）
- `SecurityConfig` 新增写接口 ADMIN；无迁移脚本（`STATUS` 列已是 varchar，`099` 菜单种子已有 M1-2-4）

### 与规格的关系

| 规格条目 | 处理 |
|---|---|
| **FR-4.1-1-6 行 513 影响分析** | **补齐**（明文 gap：列出活跃引用，确认后方可停用） |
| **行 516 未完成单据引用 L1 硬阻断 + 引用列表** | 部分补齐：替代引用真实阻断；PO/SO/BOM 桩 + 页面明示 |
| **行 514 停用通知单** | TODO 桩（下游模块未开发，事件日志留痕） |
| **行 511/515 归档（迁归档库 + 仅管理员 + 原因）** | **降级实现**：标记式归档（状态 2），不迁库；权限与原因照做 |
| **行 510/554 停用后新业务不可引用、历史可查** | 已有（`options()` 仅取启用项），回归覆盖 |
| 行 719 S-4.1-06 + BR-4.1-12~15 | 停用与替代联动沿用替代物料变更成果 |
| **重新启用** | **规格空白处补强**（误停用自愈；偏差表记「规格未覆盖，已补规则」） |
| 批量停用 | **规格空白处补强**（操作效率，逐条语义同分类合并的批量改挂） |

**已沿基线**：无审批、立即生效、版本快照记变更后值、乐观锁 verNo、禁硬删、写接口 ROLE_ADMIN。

## Capabilities

### New Capabilities
- `item-disable-archive`: 物料停用工作台 —— 影响分析（替代引用真实阻断 + 下游桩明示）、单条/批量停用、启用回退、标记式归档、通知单事件桩

### Modified Capabilities
<!-- 无：openspec/specs/ 为空（前九变更未归档）；add-item-master / add-substitute-material 的 delta 未合入，停用行为增强并入本能力 -->

## Impact

- **后端**：`MdmItemService(Mpl)`（`disable` 补原因 + `enable`/`archive`/`impact` 新方法）、`MdmItemController`（`POST /items/disable-batch`、`PUT /items/{id}/enable|archive|impact`）、`MdmItemDao`（如需归档计数复用现有反查 SQL）
- **前端**：`views/mdm/item-disable/`（新）、`api/mdm/item-disable.js`、`router/index.js` 注册 `/m/1.2.4`、`item/index.vue` 行内停用逻辑不动
- **数据**：无新表/新列/迁移；`STATUS` 语义由 `1/0` 扩展为 `1/0/2`
- **依赖**：复用 `add-substitute-material` 的 `findSourcesByTarget`/`countSourcesByTarget` 与三校验；下游 PO/SO/BOM、事件总线维持既有 TODO 桩
