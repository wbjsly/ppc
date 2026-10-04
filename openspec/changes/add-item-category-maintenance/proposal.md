# Proposal

## Why

菜单 1.2.3「分类维护」指向占位页。物料分类是**编码规则权威**（`FG/RM/WIP + 4位分类码 + 6位流水`，BR-4.1-07）与属性集绑定的载体，`add-item-master` 已建表与种子并明确「管理界面留待 1.2.3」—— 本变更补齐该缺口。规格对常规维护无明文，重头在流程四「分类树重构」（影子树/方案单/试运行/窗口调度），经需求沟通（2026-10-02）确定范围：**常规维护 + 受限的轻量重构**，完整重构流程作为 Non-Goal 单独排期。

## What Changes

- **常规分类维护**（3 级树，复用组织单元已验证的成环/深度/同层校验）：新建子分类、改名/改负责人/改层级、停用
- **编码规则字段锁定**：`ITEM_PREFIX` 与 `CATEGORY_CODE` 创建后不可改（防存量物料前缀校验漂移，BR-4.1-07），其余字段自由改
- **停用双重阻断**：有启用子孙 → 阻断列出（DC-06 模式）；被启用物料引用 → 阻断列出（DC-04）
- **轻量重构三操作**（规格外扩展，受限实现）：
  - **合并**：源 → 目标，**仅允许同 `ITEM_PREFIX`**（否则存量物料编码前缀与新分类码失配 → L1 阻断）；子分类改挂目标；**存量物料 `CATEGORY_CODE` 批量改挂 + 逐条生成版本快照**（对齐流程三 DC-03 固化语义）；源分类自动停用（软）；执行前**影响提示**（受影响物料数 + 子分类数，确认后执行）；操作原因必填
  - **迁移**：节点改挂新父级，存量物料与编码不受影响，节点变更入分类版本快照；原因必填
  - **新增**：即常规新建
- **分类自身版本审计**：迁移 `018` 新建 `erp_mdm_item_category_version`（`CHANGE_REASON` 必填于变更/合并/迁移、V(N+1) 快照、乐观锁）—— 沿七轮基线
- 前端 `views/mdm/item-category/`，菜单 `M1-2-3`（`/m/1.2.3`）切真实页；`SecurityConfig` 写接口 ADMIN；`GET /item-categories` 只读接口扩展为树形含层级

### 与规格的已知偏差（需求沟通确认，2026-10-02）

| 规格条目 | 规格要求 | 本轮决策 |
|---|---|---|
| **FR-4.1-4-1~4 流程四全部** | 影子分类树设计、重构方案单、映射规则 Excel 导入、属性集预检 | **Non-Goal**（依赖 Excel 导入与审批，单独排期） |
| **FR-4.1-4-2** | 影响分析报告 + 双人复核审批（C-0-03、3 工作日） | **Non-Goal**（无审批基线）；以合并前影响提示替代报告 |
| **BR-4.1-20~24** | 属性集预检 L1、切换窗口 L3、失败率>5% 回滚 L3、抽样 100% L1、未清>1000 分批 L4 | **Non-Goal**（随流程四） |
| **C-4.1-09、BR-4.1-01、行 603** | 切换窗口挂起新物料申请、影子树隔离、Retired 只读态 | **Non-Goal** |
| 行 801 | 200+ BOM 引用时改分类 → 批量影响评估 | **桩**（BOM 未建，item-change 已标 TODO） |

**「轻量重构」为规格外扩展**：规格假设重构走完整治理流程，本轮以受限的合并/迁移操作替代 —— 偏差表双记（缺什么 + 替代物是什么）。

**已对齐的规格约束**：DC-04 禁硬删/引用完整性、DC-06 层级不成环、C-4.1-01 编码不可改精神（规则字段锁定）、BR-4.1-07 编码前缀一致性（合并同前缀限制）、流程三 DC-03 版本固化（物料改挂逐条快照）。

## Capabilities

### New Capabilities
- `item-category-maintenance`: 物料分类树的常规维护（3 级 CRUD/停用 + 规则字段锁定 + 双重阻断）与受限轻量重构（同前缀合并含物料批量改挂与影响提示、迁移重挂），及分类自身版本审计

### Modified Capabilities
<!-- 无：openspec/specs/ 为空（前七变更未归档） -->

## Impact

- Affected specs: `item-category-maintenance`（新增）
- Affected code:
  - 迁移 `018-mdm-item-category-maintenance.sql`（分类版本表）
  - 后端新增 `entity/mdm/MdmItemCategoryVersion`、`dao/mdm/`、`service/mdm/MdmItemCategoryService`（含合并/迁移/物料批量改挂）、`controller/MdmItemCategoryController`（现有只读 endpoints 迁入或新增写 endpoints）
  - `MdmItemCategoryDao` 扩展（按分类查物料计数/清单）
  - `SecurityConfig` 写接口 ADMIN
  - 前端 `frontend/src/api/mdm/item-category.js`、`views/mdm/item-category/index.vue`、`router/index.js` 注册 `/m/1.2.3`
- 影响面：物料新建的分类下拉数据源不变；**合并后**存量物料 `CATEGORY_CODE` 变化会影响其后续变更的分类判定（CRITICAL）—— 预期内
