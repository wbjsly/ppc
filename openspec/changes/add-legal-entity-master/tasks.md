## 1. 后端数据层
- [x] 1.1 新增迁移 `backend/src/main/resources/db/mysql/010-mdm-legal-entity.sql`：`erp_mdm_legal_entity`（含编码唯一索引、状态索引、BaseEntity 公共字段）与 `erp_mdm_legal_entity_version`（`ENTITY_ID + VERSION_NO` 唯一），附 `LE-0001` 示例种子
- [x] 1.2 实体 `entity/mdm/MdmLegalEntity.java`（`@TableName("erp_mdm_legal_entity")`，继承 BaseEntity）与 `MdmLegalEntityVersion.java`
- [x] 1.3 DAO `dao/mdm/MdmLegalEntityDao.java`、`MdmLegalEntityVersionDao.java`（BaseMapper + 按编码/税号查重的 `@Select`）

## 2. 后端业务层
- [x] 2.1 Service 接口 `service/mdm/MdmLegalEntityService.java`：page/query、create、update、disable、options、versions、diff
- [x] 2.2 ServiceImpl：编码生成（`LE-` + 4 位流水，唯一索引兜底重试一次）；编码不可改校验（C-4.1-01）；税号查重返回近 3 条（C-4.1-07）
- [x] 2.3 ServiceImpl：变更前写版本快照 `V(N+1)` + 乐观锁冲突提示；停用前下游引用校验桩（C-4.1-03，返回空引用清单 + TODO 标注）
- [x] 2.4 Controller `controller/MdmLegalEntityController.java`（`/api/mdm/legal-entities`，R 包装，写接口校验 ROLE_ADMIN 语义由 SecurityConfig 承担）

## 3. 后端安全与验证
- [x] 3.1 `SecurityConfig` 增加 `/api/mdm/legal-entities` 写方法 `hasRole("ADMIN")`，GET 认证即可
- [x] 3.2 `mvn -DskipTests package`（JDK 17）编译通过；重启后端，迁移脚本自动执行且 marker 记录新增 `010-mdm-legal-entity.sql`

## 4. 前端
- [x] 4.1 API 层 `frontend/src/api/mdm/legal-entity.js`（list/create/update/disable/options/versions/diff）
- [x] 4.2 列表页 `views/mdm/legal-entity/index.vue`：筛选（编码/名称/状态）+ 表格 + 分页 + 新建/编辑弹窗 + 详情抽屉 + 停用确认（含引用清单回显）
- [x] 4.3 版本历史子组件：版本列表 + 两版本逐字段 diff 对比
- [x] 4.4 `router/index.js` 注册 `/m/1.1.1` → `views/mdm/legal-entity/index.vue`（替换占位路由命中）

## 5. 端到端验证
- [x] 5.1 接口冒烟：admin 新建 → 列表可见 `LE-000X` → 变更 → 版本表出现 V1/V2 → 停用 → options 接口不再返回；`verNo` 冲突返回业务错误
- [x] 5.2 浏览器实测：登录后侧栏「基础数据 > 组织管理 > 法人主体」进入真实页，完成新建/变更/版本对比/停用全流程；`ROLE_USER` 账号写操作返回 403
- [x] 5.3 权限验证：未登录调用 GET 返回 401
- [x] 5.4 `openspec validate add-legal-entity-master --strict --no-interactive` 通过，任务全项勾选
