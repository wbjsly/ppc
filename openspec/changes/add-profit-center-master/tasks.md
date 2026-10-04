# Tasks

## 1. 后端数据层

- [x] 1.1 新增迁移 `012-mdm-profit-center.sql`：`erp_mdm_profit_center`（`PC_CODE` 唯一索引、`LEGAL_ENTITY_ID`/`STATUS` 索引、BaseEntity 公共字段）+ `erp_mdm_profit_center_version`（`ENTITY_ID+VERSION_NO` 唯一）+ 种子 `PC-0001` 挂 `le-0001`；重启后端验证 marker 记录 012、`SHOW TABLES` 可见两新表
- [x] 1.2 新增迁移 `013-cost-center-profit-center.sql`：`ALTER TABLE erp_mdm_cost_center ADD COLUMN IF NOT EXISTS PROFIT_CENTER_ID VARCHAR(64)` + 存量 `CC-PROD-01` 回填 `PC-0001`；重启后端验证列存在且种子行已回填
- [x] 1.3 实体 `MdmProfitCenter`/`MdmProfitCenterVersion` + DAO ×2；`MdmCostCenter` 实体加 `profitCenterId` 字段（`@TableField` 默认映射）；编译通过

## 2. 后端业务层

- [x] 2.1 Service 接口 + Impl：page/create/update/disable/options/versions/diff，`PC-` 4 位流水生成（唯一索引冲突重试）、编码不可改、税号式查重语义免（利润中心无税号，跳过）、版本快照记变更后新值 + diff 摘要、乐观锁 409
- [x] 2.2 Controller `/api/mdm/profit-centers` 9 个端点 + `SecurityConfig` 写方法 `hasRole("ADMIN")`；编译打包（JDK 17）通过
- [x] 2.3 成本中心侧改造：create/update 增加利润中心同主体校验（跨主体 422 阻断）；变更所属主体时原利润中心不同主体 → 自动清空 `profitCenterId`；`buildDiff`/`readField`/`toJson`/`compareSnapshots` 的 `SNAPSHOT_FIELDS` 追加 `profitCenterId`
- [x] 2.4 利润中心 disable：查归属它的启用成本中心（`profit_center_id = ? AND status = '1'`），非空 409 阻断并列出清单；下游凭证行引用校验桩标 TODO；成本中心 `options` 返回体增加 `profitCenterId`
- [x] 2.5 接口冒烟（admin 脚本）：新建生成 `PC-0002` → 改编码 422 → 变更 verNo 递增 + V2 快照 → 旧 verNo 409 → 成本中心挂同主体利润中心成功 → 跨主体挂载 422 → 归属出现在 diff → 停用有归属启用成本中心的利润中心 409 → 停用叶子利润中心 200 → options 过滤

## 3. 前端

- [x] 3.1 API 层 `api/mdm/profit-center.js`；`views/mdm/profit-center/index.vue`：主体/关键字/状态筛选 + 表格 + 分页 + 新建/变更弹窗 + 详情抽屉 + 版本历史与逐字段对比 + 停用确认（阻断清单回显）
- [x] 3.2 成本中心表单增加「利润中心」下拉（按所属主体过滤、可空）+ 归属展示列；改主体时前端提示归属将被重置；浏览器验证下拉只出现同主体启用项
- [x] 3.3 `router/index.js` 注册 `/m/1.1.3` → `views/mdm/profit-center/index.vue`；浏览器验证侧栏「基础数据 > 组织管理 > 利润中心」进入真实页

## 4. 端到端验证（集成检查）

- [x] 4.1 浏览器完整流程：新建利润中心 → 成本中心表单挂归属 → 跨主体选项不可见 → 改主体后归属重置 → 详情版本对比 → 停用阻断回显
- [x] 4.2 权限：`ROLE_USER` 写 403 / 读 200；未认证 401（利润中心与成本中心归属写路径均验证）
- [x] 4.3 回归：法人主体与成本中心页面无损（菜单切换、既有树形操作正常）；测试数据清理，仅保留种子 `PC-0001` 与 `CC-PROD-01`（归属已回填）
- [x] 4.4 `openspec validate add-profit-center-master --strict --no-interactive` 通过，全部任务勾选
