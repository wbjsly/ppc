# Tasks

## 1. 菜单与文档（后端资源，无 Java 改动）

- [x] 1.1 改 `docs/design/08-erp-menu.md` 1.2 子树：删 1.2.2/1.2.4/1.2.5，1.2.1 改「物料管理」，1.2.3 改号 1.2.2「分类维护」；grep 全仓 `1.2.[1-5]` 交叉引用并列出受影响清单（验证：08 文档 1.2 子树仅 2 项、引用清单产出）
- [x] 1.2 `node backend/scripts/generate-menu-seed.cjs` 重生成 099；diff 确认仅 1.2 子树变化（验证：099 中 1.2 行为 M1-2-1/M1-2-2 两行，git/文本 diff 无其它域变化）
- [x] 1.3 新迁移 `100-item-menu-consolidate.sql`（先清后插，见 design D5），重启后端执行；验证 marker 100、存量 `erp_admin_menu` 仅 `M1-2-1 物料管理` + `M1-2-2 分类维护`，且与 099 产物逐字段一致；重复重启结果不变（幂等）

## 2. 前端整合（方案 C 单列表）

- [x] 2.1 `views/mdm/item/` 子组件抽取：`ItemFormDialog.vue`（新建/变更表单，含查重/477/forceCreate）、`SubstituteDialog.vue`、`ImpactDrawer.vue`、`LifecycleDialog.vue`（停/启/归，原因必填+终态警示+影响摘要）；各子组件从原三页面迁移逻辑（验证：编译通过，子组件各自 ≤200 行量级）
- [x] 2.2 后端 `GET /items` 分页加 `hasSubstitute` 参数（1 有替代/0 无替代，`MdmItemService(Mpl)` + Controller，design D2 修正项）；重写 `item/index.vue` 为装配层：工具栏（新建+批量停用+关键字/状态/有无替代/维度筛选）、列（替代指向含失效红标）、行操作状态机（1→变更/停/替代/影响；0→变更/启/归/替代/影响；2→仅影响+终态）；D2 双端点切换与行结构标准化（验证：`/m/1.2.1` 单页承载全部动作，两种端点切换列表渲染正确）
- [x] 2.3 路由：`/m/1.2.1`→item 页；`/m/1.2.2`→item-category（原 1.2.2 mode-change 条目删除）；`/m/1.2.4`、`/m/1.2.5` redirect→`/m/1.2.1`；删除 `meta.mode`/`isChangeMode`（验证：四路径跳转行为符合 spec）
- [x] 2.4 删除 `views/mdm/item-disable/`、`views/mdm/substitute/` 目录与失效引用（grep 确认无残留 import；`api/mdm/item-disable.js`、`substitute.js` 保留）（验证：构建通过、grep 零引用）

## 3. 端到端验证

- [x] 3.1 浏览器单页全流程：菜单树仅「物料管理/分类维护」→ 新建（自动编码+查重）→ 行内变更 → 行内停用（原因必填）→ 批量停用（勾选仅启用行）→ 启用回退 → 归档（终态行仅影响分析）→ 替代设置/清除 → 维度正向/反查切换 → 有无替代筛选 → 影响分析抽屉（桩文案）→ 阻断回显（409 引用清单/422 失效目标）；console 无 error/warn
- [x] 3.2 路由兼容与回归：`/m/1.2.4`、`/m/1.2.5` 重定向到物料管理；`/m/1.2.2` 呈现分类维护；分类维护全流程（新建/变更/停用/合并/迁移）无损；菜单管理页 M1-2 子树展示正确；`ROLE_USER` 写操作 403、未认证 401
- [x] 3.3 测试数据清理至种子态（1 物料启用无替代、0 版本、5 分类、菜单 1.2 仅 2 行）
- [x] 3.4 `openspec validate consolidate-item-menus --strict --no-interactive` 通过，全部任务勾选
