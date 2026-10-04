# Design

## Context

见 proposal.md（Why）。现状关键点：

- 三页面组件：`item/index.vue`（571 行，`meta.mode` 切 create/change）、`item-disable/index.vue`（261 行）、`substitute/index.vue`（163 行）—— 均为「物料列表 + 动作集」
- 菜单：`docs/design/08-erp-menu.md` 为源，`node backend/scripts/generate-menu-seed.cjs` 生成 `099-menu-seed.sql`（勿手工编辑）
- **099 已执行**（marker `099-menu-seed.sql` 2026-09-30），`INSERT IGNORE` 不更新存量行 → 存量库必须靠新迁移修正
- API 层已就绪：`item.js`（分页 keyword/categoryCode/status）、`item-disable.js`（impact/enable/archive/batch）、`substitute.js`（正反查 substitutes）
- 后端端点基本复用；**唯一后端改动**：`GET /items` 分页加 `hasSubstitute`（1 有替代 / 0 无替代 / 空全部）——spec 要求「是否配置替代」筛选，而 substitutes 端点只返回 ALT 非空行，「无替代」无后端支撑，分页接口是最小落点（实施中发现，design 原「零改动」承诺据此修正）

## Goals / Non-Goals

**Goals:**
- 四菜单 → 一入口单列表，动作全集无损（spec 回归要求）
- 主页面可维护：列表 + 工具栏 + 弹窗编排留在主文件，动作实现下沉子组件
- 菜单源（08 文档）→ 生成物（099）→ 存量库（100 迁移）三处一致
- 旧链接不落 404/占位页

**Non-Goals:**
- 不改任何后端接口与业务规则
- 不合并分类维护（独立保留）
- 不处理 1.2.x 以外的菜单编号
- 不做菜单管理页的批量编辑增强

## Decisions

### D1 主页面 = 装配层，动作下沉为 `views/mdm/item/` 子组件
```
views/mdm/item/
  index.vue            # 列表 + 工具栏 + 筛选 + 弹窗编排（主文件，目标 ~350 行）
  ItemFormDialog.vue   # 新建/变更表单（原 item 表单迁移：查重、477、forceCreate）
  SubstituteDialog.vue # 设置替代弹窗（原 substitute 弹窗）
  ImpactDrawer.vue     # 影响分析抽屉（原 item-disable 抽屉）
  LifecycleDialog.vue  # 停用/启用/归档统一确认（原因必填 + 终态警示 + 影响摘要）
```
- **理由**：方案 C 要求单列表，但 571+261+163 ≈ 995 行揉一个文件不可维护；子组件按「动作」切分，每个 ≤200 行
- **替代方案**：保持三页面仅包 tab 壳（方案 B）—— 用户已否；复用整页组件再传 props 组合 —— 三页面都自带列表（重列表），只能复用动作子组件而非页面

### D2 筛选合并：一个 `query` 状态对象
```
query = { keyword, status, hasSubstitute, dimension(source/target), current, size }
```
- **数据源取舍**：列表主数据用 `GET /items`（支持 status）；但「有无替代 / 反查维度」是 substitutes 端点的能力
- **决策**：默认视图（不过滤替代维度时）走 `/items`；一旦用户选了 `hasSubstitute` 或 `dimension=target`，切换到 `/items/substitutes` 端点（返回行结构不同：`ITEM_CODE`/`TARGET_*` 大写 key）
- **理由**：不改后端；用 `rowOf(row, UPPER, lower)` 兼容两种行结构（替代页已有此模式）
- **代价**：两种端点的分页/列结构需在列表层适配 —— 主页面行标准化函数统一转小写 key 后再渲染，避免模板里到处 `rowOf`

### D3 列合并与行操作状态机
- 列：编码 | 名称 | 分类 | 状态 | 替代指向（空 `—`、指向已失效红标，数据来自行内 `altItemCode` + 替代端点的 `TARGET_STATUS`）| 操作
- 行操作按状态显示（spec 已定义）：`1`→变更/停用/替代/影响；`0`→变更/启用/归档/替代/影响；`2`→仅影响分析 + 终态标注
- 变更动作原为 1.2.2 独立模式，现为行内按钮打开 `ItemFormDialog`（编辑态）；`isChangeMode`、`meta.mode` 删除

### D4 路由与重定向
```js
/m/1.2.1 → item/index.vue        // 物料管理（唯一新入口）
/m/1.2.2 → item-category/         // 分类维护（改号）
/m/1.2.4, /m/1.2.5 → redirect: '/m/1.2.1'
```
- 旧变更 `/m/1.2.2` 与新分类维护同路径 → 新语义直接覆盖（spec 已记录取舍），无需也无法重定向
- `router/index.js` 中 `/m/1.2.2` 原 `mode:'change'` 条目删除

### D5 菜单三处同步的顺序与幂等
1. 改 `08-erp-menu.md` 1.2 子树（5 项 → 2 项：1.2.1 物料管理、1.2.2 分类维护）
2. `node backend/scripts/generate-menu-seed.cjs` 重生成 099（全新安装路径）
3. 新迁移 `100-item-menu-consolidate.sql`（存量库）

**生成器 ID 规则已确认**（读 `generate-menu-seed.cjs`）：行 ID = `M{域}-{模}-{文档序号}`（如 `M1-2-1`），从 08 文档标题编号推导。因此重编号后：
- 全新安装 099 产出：`M1-2-1` 物料管理、`M1-2-2` 分类维护（INSERT IGNORE）
- 存量库 100 迁移，为与全新安装终态**逐行一致**，采用「先清后插」：
```sql
DELETE FROM erp_admin_menu WHERE ID IN ('M1-2-1','M1-2-2','M1-2-3','M1-2-4','M1-2-5');
INSERT INTO erp_admin_menu (ID, PARENT_ID, MENU_CODE, TITLE, PATH, ICON, SORT_ORDER, PERM, STATUS) VALUES
  ('M1-2-1','M1-2','1.2.1','物料管理','/m/1.2.1','', '10','','1'),
  ('M1-2-2','M1-2','1.2.2','分类维护','/m/1.2.2','', '20','','1');
```
- **幂等**：重复执行删除+插入得到相同结果；先 DELETE 覆盖了「旧行 ID 与新行 ID 交叠」问题（`M1-2-2` 旧=变更、新=分类维护），无需处理唯一键冲突顺序
- **一致性验证**：迁移后存量库两行应与 099 重生成产物逐字段相等（任务中含 diff 校验）

### D6 删除文件的回归保护
- `item-disable/`、`substitute/` 删除前，先确认无其它引用（router、菜单、互相 import）
- API 文件 `item-disable.js`、`substitute.js` **保留**（新页面 import 它们）

## Risks / Trade-offs

- [两种端点行结构不一致（小写 vs 大写 key）] → D2 行标准化函数入口统一转换，单测/冒烟双端点各验一次
- [995 行三页面合一可能丢动作] → spec 场景逐条回归（新建/变更/停用/批量/启用/归档/正反查/影响/477/阻断回显）
- [100 迁移与 099 重生成的 ID/唯一键冲突] → 实施先读 generate-menu-seed.cjs 输出确认 ID 规则；迁移内先删后改
- [菜单编号变化影响 08 文档其它交叉引用] → 改 08 后 grep `1.2.` 全文档与代码，确认仅 1.2 子树变化
- [旧书签 /m/1.2.2 呈现分类维护] → 已知取舍记入 spec；菜单侧变更入口从行内可达

## Migration Plan

1. 改 08 文档 → 重生成 099（对全新安装正确）
2. 写 100 迁移（对存量库正确）→ 重启后端触发 DbBootstrap
3. 前端路由/页面同步切换（同一变更内原子完成）
4. 回滚：回退代码 + 100 迁移本身幂等（重跑恢复逻辑见 D5 反向即可，但归档内不写回滚脚本——菜单是低风险配置数据）

## Open Questions

（无 —— 生成器 ID 规则已在 D5 中确认并落定迁移写法。）
