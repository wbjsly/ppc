## 1. 后端数据层
- [x] 1.1 新增迁移 `backend/src/main/resources/db/mysql/011-mdm-cost-center.sql`：`erp_mdm_cost_center`（`CC_CODE` 唯一索引、`LEGAL_ENTITY_ID`/`STATUS` 索引、`PARENT_ID`、`TREE_LEVEL`、`COST_TYPE`、`IS_DEFAULT`、BaseEntity 公共字段）与 `erp_mdm_cost_center_version`（`ENTITY_ID + VERSION_NO` 唯一），附 `CC-PROD-01` 挂 `le-0001` 的种子
- [x] 1.2 实体 `entity/mdm/MdmCostCenter.java`（`@TableName("erp_mdm_cost_center")`）与 `MdmCostCenterVersion.java`
- [x] 1.3 DAO `dao/mdm/MdmCostCenterDao.java`、`MdmCostCenterVersionDao.java`（BaseMapper + 按类型取最大编码的 `@Select` + 同主体默认中心查询）

## 2. 后端业务层
- [x] 2.1 Service 接口 `service/mdm/MdmCostCenterService.java`：tree 查询（按主体/关键字/状态）、create、update、disable、setDefault、options（按主体过滤）、versions、diff
- [x] 2.2 ServiceImpl 编码生成：`COST_TYPE → 类型码` 映射（PROD/SALES/ADMIN/RD），按类型独立流水 2 位，唯一索引冲突重试；编码前缀与类型一致性校验
- [x] 2.3 ServiceImpl 树校验：`TREE_LEVEL ≤ 3` 阻断；改父节点时回溯新父链做 DC-06 成环校验并重算层级；停用时列出未停用子孙阻断
- [x] 2.4 ServiceImpl 默认中心：同主体唯一，事务内清除旧默认并写快照；停用/撤销余额校验桩（同 `queryDownstreamReferences` 模式，标 TODO）
- [x] 2.5 版本快照（变更后新值 + diff 摘要）与乐观锁，复用法人主体模式
- [x] 2.6 Controller `controller/MdmCostCenterController.java`（`/api/mdm/cost-centers`，R 包装）

## 3. 后端安全与构建
- [x] 3.1 `SecurityConfig` 追加 `/api/mdm/cost-centers` 写方法 `hasRole("ADMIN")`，GET 认证即可
- [x] 3.2 `mvn -DskipTests package`（JDK 17）通过；重启后端，迁移 `011-mdm-cost-center.sql` 自动执行且 marker 记录新增

## 4. 前端
- [x] 4.1 API 层 `frontend/src/api/mdm/cost-center.js`（tree/create/update/disable/setDefault/options/versions/diff）
- [x] 4.2 树形列表页 `views/mdm/cost-center/index.vue`：主体下拉 + 关键字/状态筛选 + `el-table` 树形展示（层级/编码/名称/类型/默认标记/状态）+ 新建（父节点选择，校验深度）/变更弹窗 + 详情抽屉 + 停用确认
- [x] 4.3 版本历史子组件：版本列表 + 两版本逐字段 diff（含 parentId/treeLevel）
- [x] 4.4 `router/index.js` 注册 `/m/1.1.2` → `views/mdm/cost-center/index.vue`（替换占位路由命中）

## 5. 端到端验证
- [x] 5.1 接口冒烟：admin 新建根节点（`CC-PROD-0x`）→ 新建二级 → 尝试 4 级阻断 → 成环改父阻断 → 变更生成 V2 → 置默认清除同主体旧默认 → 停用有子孙节点阻断 → 叶子停用成功 → options 按主体过滤
- [x] 5.2 乐观锁与权限：旧 `verNo` 变更 409；`ROLE_USER` 写 403 / 读 200；未认证 401
- [x] 5.3 浏览器实测：侧栏「基础数据 > 组织管理 > 成本中心」进入真实页，完成 新建→改父→版本对比→设默认→停用 全流程
- [x] 5.4 `openspec validate add-cost-center-master --strict --no-interactive` 通过，任务全项勾选
