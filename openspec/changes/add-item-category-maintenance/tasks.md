# Tasks

## 1. 后端

- [x] 1.1 迁移 `018-mdm-item-category-maintenance.sql`：`erp_mdm_item_category_version`（`ENTITY_ID+VERSION_NO` 唯一、`SNAPSHOT_JSON`/`DIFF_SUMMARY`/`OP_TYPE` 含 MERGE/MOVE/`CHANGE_REASON`）；重启后端验证 marker 018 与表存在
- [x] 1.2 `MdmItemCategoryVersion` 实体 + DAO；`MdmItemCategoryDao` 扩展（按分类查启用物料计数与前 10 条编码、查子分类）；编译通过（JDK 17）
- [x] 1.3 `MdmItemCategoryService` + Impl：树查询（扩展只读接口为含层级树形态，另保留原平铺接口给物料表单）、新建、变更（**前缀/分类码锁定 422**、原因必填、成环/深度、V(N+1)）、停用（**双重阻断**：启用子孙清单 + 启用物料清单 LIMIT 10+总数）
- [x] 1.4 合并与迁移：合并校验（同前缀 422、原因必填、影响面统计接口）→ 单事务执行（子分类改挂 + 存量物料批量改挂 + **逐条物料版本快照** + 源分类停用 + 分类版本记录）；迁移（父节点改挂 + 成环/深度 + 原因 + 版本快照，物料不动）；>5000 条提示确认桩
- [x] 1.5 `MdmItemCategoryController`（写接口）+ `MdmItemServiceImpl.requireCategory` **补启用状态校验**（停用分类不可被新物料引用，design Decision 风险项）+ `SecurityConfig` 写接口 ADMIN；编译打包通过
- [x] 1.6 接口冒烟：新建子分类 4 位码 → 4 级阻断 → 改前缀 422 → 改名+原因 V2 → 停用有子孙 409 → 停用被物料引用 409（列物料码）→ 跨前缀合并 422 → 同前缀合并成功（物料改挂+逐条快照+源停用）→ 影响面接口数字正确 → 迁移成功（物料 CATEGORY_CODE 不变）→ 旧 verNo 409 → 停用分类后新建物料 422

## 2. 前端

- [x] 2.1 API 层 `api/mdm/item-category.js`；`views/mdm/item-category/index.vue`：树形表格 + 新建/变更弹窗（前缀与 4 位码编辑态禁用 + 原因必填）+ 停用确认（阻断清单回显）
- [x] 2.2 **合并弹窗**（目标选择仅同前缀 + 影响提示「受影响物料 N / 子分类 M」+ 原因必填 + 确认执行）与**迁移弹窗**（父节点选择 + 原因必填）
- [x] 2.3 详情抽屉：版本历史（含 MERGE/MOVE 标签、原因）与逐字段对比；`router/index.js` 注册 `/m/1.2.3` → `views/mdm/item-category/index.vue`

## 3. 端到端验证

- [x] 3.1 浏览器全流程：新建子分类 → 前缀锁定阻断回显 → 改名+原因 → 停用双重阻断回显（子孙/物料两场景）→ 合并影响提示与执行 → 版本历史见 MERGE+原因 → 迁移执行
- [x] 3.2 权限：`ROLE_USER` 合并/变更 403；未认证 401；回归 1.2.1/1.2.2 页面与物料表单分类下拉无损（合并后物料分类下拉数据正确）
- [x] 3.3 测试数据清理：合并/迁移操作回滚到种子态（5 分类、物料挂 RM0001、种子版本链清空）
- [x] 3.4 `openspec validate add-item-category-maintenance --strict --no-interactive` 通过，全部任务勾选
