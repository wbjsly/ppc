# Proposal

## Why

菜单 1.2.2「物料变更」指向占位页。规格流程二（FR-4.1-2-1~4）与四条变更专属规则（BR-4.1-12~15）定义了物料主数据变更的完整语义：**变更原因 + 新旧值自动对比 + 关键属性分类 + 版本发布 + 差异同步**。`add-item-master` 已交付列表内基础变更（乐观锁、V(N+1) 快照、编码不可改），但缺变更原因审计、分类判定、差异预览与编码阻断跳转 —— 本变更补齐流程二的剩余能力，并将 1.2.2 菜单从占位页切换为真实变更入口。

## What Changes

- **迁移 `017-item-change-audit.sql`**：`erp_mdm_item_version` 加 `CHANGE_REASON VARCHAR(255)`（变更原因，变更起必填，V1 为空）与 `CHANGE_TYPE VARCHAR(16)`（分类判定落库）
- **变更分类判定**（FR-4.1-2-2）：差异字段命中关键属性清单 {计量单位、物料分类、采购类型} → `CRITICAL`，否则 `GENERAL`；**仅判定与警示，不走审批**（2026-10-02 用户确认）
- **`MdmItemService.update` 扩展**：`reason` 必填校验、`changeType` 计算落库、写入版本记录
- **变更弹窗增强**（前端 `views/mdm/item/`）：
  - 变更原因输入（必填）
  - **提交前新旧值差异预览**（前端实时对比，与 `diffSummary` 同口径）
  - **关键属性变更警示标签**（命中清单 → 顶部醒目提示，供未来审批路由留载体）
  - **编码阻断跳转向导**（BR-4.1-13 后半）：变更含编码修改 → 阻断消息带「去新建」→ 打开新建弹窗**预填可继承属性**（名称/分类/单位/物料组/采购类型/存储条件，编码留空）
- **1.2.2 页面**：物料列表 + 「发起变更」入口，**复用 item 组件**（同构 1.2.1，聚焦变更动作）
- **桩**：`MDM.ITEM.UPDATED` 差异事件（BR-4.1-15，无 MQ，TODO，取材 `diffSummary`）、200+ BOM 引用分类阻断（行 801，BOM 未建，TODO）

### 与规格的已知偏差（需求沟通确认，2026-10-02）

| 规格条目 | 规格要求 | 本轮决策 |
|---|---|---|
| **BR-4.1-12 / C-4.1-02** | 关键属性变更强制**双人复核**（复核人≠发起人），L2 | **分类判定 + 警示标签落库，无审批**（审批载体保留，待未来工作流；偏差记录） |
| FR-4.1-2-3 | 审批通过后发布、写生效日期 | **无审批，保存立即生效**（版本 N+1 快照保留） |
| **BR-4.1-14 后半** | 生效日期不得早于当前日期 | **不做**（无生效日期机制，沿基线） |
| **BR-4.1-15 后半** | 事件重试 `API_RETRY_MAX` 超限 → 运维工单 | **桩**（无事件总线，diffSummary 已含差异字段可供未来取材） |
| **行 801** | 物料被 200+ 生效 BOM 引用时改分类 → 批量影响评估 + 锁定编辑 | **桩**（BOM 模块未落地，TODO 回补） |

**已对齐的规格约束**：FR-4.1-2-1（变更原因必填 + 新旧值自动对比）、FR-4.1-2-2（变更分类判定 + 编码不可改）、BR-4.1-13（L1 阻断 + 跳转向导预填）、BR-4.1-14 前半（版本 N+1 快照）、C-4.1-06（版本可回溯）。

## Capabilities

### New Capabilities
- `item-change-management`: 物料变更全链路 —— 变更原因审计、关键属性分类判定与警示、提交前差异预览、编码阻断跳转向导、1.2.2 变更入口页

### Modified Capabilities
<!-- 无：openspec/specs/ 为空（前六变更未归档）；add-item-master 的 delta 尚未合入 specs/，本变更新增能力与其正交（变更 vs 新建） -->

## Impact

- Affected specs: `item-change-management`（新增）
- Affected code:
  - 迁移 `backend/src/main/resources/db/mysql/017-item-change-audit.sql`（版本表加两列）
  - 后端 `MdmItemServiceImpl.update`（reason/changeType）、`MdmItemVersion` 实体、`MdmItemService` 接口签名、Controller
  - 前端 `views/mdm/item/index.vue`（变更弹窗增强 + mode 支持）、`router/index.js` 注册 `/m/1.2.2`、`api/mdm/item.js` 参数
- 不改：物料表结构（`erp_mdm_item` 不动）、新建流程、其余五模块
