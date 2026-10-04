# Tasks

## 1. 后端数据层

- [x] 1.1 新增迁移 `014-mdm-org-unit.sql`：`erp_mdm_org_unit`（`OU_CODE` 唯一索引、`LEGAL_ENTITY_ID`/`STATUS`/`PARENT_ID` 索引、`OU_TYPE`/`TREE_LEVEL`/BaseEntity 公共字段）+ `erp_mdm_org_unit_version`（`ENTITY_ID+VERSION_NO` 唯一）+ 种子 `OU-FACTORY-01` 挂 `le-0001`；重启后端验证 marker 记录 014、`SHOW TABLES` 见两新表
- [x] 1.2 实体 `MdmOrgUnit`（含 `children` 非持久化字段）/`MdmOrgUnitVersion` + DAO ×2（`selectMaxCodeByType`、`selectChildren`）；编译通过（JDK 17）

## 2. 后端业务层

- [x] 2.1 Service 接口 + Impl：`tree`（主体/类型/关键字/状态）、create/update/disable/options/versions/diff；六类 `TYPE_CODES` 校验 + `OU-{类型码}-{2位流水}` 按类型生成（前缀长度 `3+len+1` 防偏移，唯一索引冲突重试）+ 编码前缀与类型一致校验
- [x] 2.2 树形三件套：`TREE_LEVEL ≤ 3` 阻断、改父节点回溯 DC-06 成环 + 同主体 + 深度校验、停用列出未停用子孙阻断；C-0-08 下游引用桩标 TODO；版本快照记变更后新值 + diff 摘要 + 乐观锁
- [x] 2.3 Controller `/api/mdm/org-units` + `SecurityConfig` 写方法 `hasRole("ADMIN")`；编译打包通过
- [x] 2.4 接口冒烟：新建 `OU-FACTORY-02` → 异构子节点 `OU-WAREHOUSE-01`（L2）→ 4 级阻断 → 成环阻断 → 改编码 422 → 改类型与编码不一致 422 → 变更 verNo 递增 + V2 快照 → 旧 verNo 409 → 停用有子孙阻断 → 叶子停用 200 → options 含 level/type 且过滤停用项 → 非法类型 422

## 3. 前端

- [x] 3.1 API 层 `api/mdm/org-unit.js`；`views/mdm/org-unit/index.vue`：主体/类型/关键字/状态筛选 + 树形表格（类型列六类标签、层级、状态）+ 新建（父节点选择、类型下拉六类，编辑态类型禁用）/变更弹窗 + 详情抽屉 + 版本历史与逐字段对比 + 停用确认
- [x] 3.2 `router/index.js` 注册 `/m/1.1.4` → `views/mdm/org-unit/index.vue`

## 4. 端到端验证

- [x] 4.1 浏览器完整流程：新建工厂根 → 异构子节点（仓库挂工厂）→ 层级/成环阻断回显 → 变更生成 V2 → 详情版本对比 → 停用阻断回显 → options 校验
- [x] 4.2 权限：`ROLE_USER` 写 403 / 读 200；未认证 401
- [x] 4.3 回归：法人主体/成本中心/利润中心三模块页面与 API 无损；测试数据清理仅留种子
- [x] 4.4 `openspec validate add-org-unit-master --strict --no-interactive` 通过，全部任务勾选
