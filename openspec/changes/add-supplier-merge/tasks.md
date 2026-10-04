# Tasks

## 1. 后端

- [x] 1.1 迁移 `024-supplier-merge.sql`：`erp_mdm_supplier` 裸 `ADD COLUMN` 两列（`MERGED_TO`/`PRE_STATUS`，013 教训 marker 幂等）+ `erp_mdm_supplier_merge_log`（无 DEL_FLAG 永久日志，`LOG_NO` 唯一）；重启后端验证 marker 024、两列与日志表存在（验证：`SHOW COLUMNS LIKE 'MERGED%'` + `SHOW TABLES LIKE 'erp_mdm_supplier_merge_log'`）
- [x] 1.2 实体 `MdmSupplier` 加 `mergedTo/preStatus`、新实体 `MdmSupplierMergeLog` + DAO（`selectMaxLogNo`、按源/目标查询）；`MdmSupplierServiceImpl` 守卫补 MERGED：`update/changeStatus/review` 拒绝 422「已合并终态，编码锁定」（矩阵与显式分支），options/sweep 天然排除核对；编译通过
- [x] 1.3 `MdmSupplierMergeService/Impl` 合并侧：`candidates`（税号+名称≤3，排除自身与 MERGED）、`compare`（付款条件/税号/开户行/证照最早有效期并排 + 方向建议文案）、`impact`（证照真实计数 + 四类桩清单）（验证：编译通过）
- [x] 1.4 `merge` 单事务五步（design D3：校验组四条含跨法人为 null==null 允许 → 证照逐条改挂 → 源 MERGED+MERGED_TO+PRE_STATUS → 日志 INSERT → 双方 MERGE 快照 + MERGED 事件）；`revert` 单事务（30 天闭区间判定/已回退 422/原因必填 → 恢复 PRE_STATUS+清 MERGED_TO → 证照回迁 → 日志回填 → REVERT 快照 + MERGE_REVERTED 事件）；日志分页查询（联表名称）（验证：编译通过）
- [x] 1.5 `MdmSupplierMergeController`（candidates/compare/impact/merge/revert/日志分页）+ `SecurityConfig` 写接口 ADMIN；编译打包通过
- [x] 1.6 接口冒烟：候选（税号命中+名称≤3、排除自身与 MERGED）→ 对比并排+方向建议 → 跨法人 422（含单侧空）→ 源=目标 422 → 已 MERGED 参与 422 → 缺原因 422 → 影响面（证照 N 真实+四类桩 note）→ 合并 200（证照改挂核对+源 MERGED/MERGED_TO/PRE_STATUS+日志行+双 MERGE 快照+MERGED 事件）→ 源变更/状态/再合并 422 → options 与 sweep 不含 MERGED → 日志分页 200 → 回退（构造 10 天前日志）200（状态恢复+证照回迁+日志回填+REVERT 快照+事件）→ 重复回退 422 → 构造 31 天前记录回退 422 → 回退后源可正常编辑 → ROLE_USER 写 403 / 未认证 401

## 2. 前端

- [x] 2.1 `api/mdm/supplier-merge.js`；`views/mdm/supplier-merge/index.vue` Tab1 合并日志（分页列表+回退状态列+**30 天内未回退显示回退按钮、超期禁用+tooltip**）（验证：页面加载、列表与按钮态正确）
- [x] 2.2 Tab2 发起合并：关键字搜索候选列表（设为源/设为目标）+ 差异对比弹窗（并排表+方向建议+**影响面四类桩清单明示**）+ 原因必填确认执行（验证：校验组 409/422 前端回显、合并成功列表刷新）
- [x] 2.3 回退弹窗（原因必填+回退影响说明）+ `router/index.js` 注册 `/m/1.4.2` + **准入页 `statusName/statusTag` 扩 MERGED 六态与行操作隐藏（design D7 跨页面）** + **cross-domain 事件流下拉补 MERGED/MERGE_REVERTED 两类型**（验证：菜单切真实页、准入页 MERGED 展示正确、事件流可筛）
- [x] 2.4 端到端浏览器补充：回退成功后准入页源恢复原状态与可操作性（验证：回退前后状态与按钮态对比）

## 3. 端到端验证

- [x] 3.1 浏览器全流程：建两家供应商（含跨法人构造）→ 跨法人阻断回显 → 差异对比弹窗（并排+建议）→ 影响面桩展示 → 合并成功（日志新行+源终态展示）→ 源操作锁定回显 → 证照改挂核对 → 回退（原因必填）→ 源恢复+证照回迁+日志已回退 → 事件流筛 MERGED/REVERTED → console 无 error/warn
- [x] 3.2 权限与回归：`ROLE_USER` 合并/回退 403、候选与日志查询 200、未认证 401；回归 1.4.1 准入（六态守卫+options/sweep）、1.3.3 事件流页面接口无损
- [x] 3.3 测试数据清理：合并日志清空、供应商 3 表清空、供应商表新列置 NULL；客户 4 表/价格协议/outbox/物料/分类归种子态（outbox 空、1 物料启用、5 分类、法人 1）
- [x] 3.4 `openspec validate add-supplier-merge --strict --no-interactive` 通过，全部任务勾选
