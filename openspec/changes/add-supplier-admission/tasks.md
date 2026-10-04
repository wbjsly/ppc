# Tasks

## 1. 后端

- [x] 1.1 迁移 `023-supplier-admission.sql`：3 表（`erp_mdm_supplier` 含 `SUPPLIER_CODE` 唯一/税号与状态索引、`erp_mdm_supplier_cert` 含 `SUPPLIER_ID` 与 `EXPIRE_DATE` 索引、`erp_mdm_supplier_version` ENTITY_ID+VERSION_NO 唯一）；重启后端验证 marker 023 与 3 表存在（验证：`SHOW TABLES LIKE 'erp_mdm_supplier%'`）
- [x] 1.2 实体 `MdmSupplier(+Cert,+Version)` + DAO（含 `findTaxNoHolder` 同款税号查重、到期证照存在性查询）；`MdmSupplierService` 接口；编译通过（验证：`mvn package` 通过）
- [x] 1.3 `MdmSupplierServiceImpl` 建档/变更：SUP-NNNN 自动编码 + 编码锁 422、四组字段必填、**黑名单 HIT 建档与变更双入口 422**、查重复用 SimilarityUtil（税号+名称≤3→3条，forceCreate+dupNote）、建档 PENDING + CREATE 快照 + outbox CREATED（验证：编译通过）
- [x] 1.4 单步审核 + 五态状态机：`review(result, reason)`（APPROVED→QUALIFIED/REJECTED 留痕保持 PENDING，均 REVIEW* 快照+事件，非 PENDING 422；**通过时校验证照无过期 422**）、`transition` 迁移矩阵单点守卫（PENDING 仅审核可动、启停冻解全链、`resolveAfterRelease` 启用恢复态判定 D5）+ 各自快照与 10 类 outbox 事件（验证：编译通过）
- [x] 1.5 证照与到期治理：证照增改（软删+入快照）、`sweepExpiredCerts` 懒巡检（page/impact/options 前置，幂等置 CERT_EXPIRED+快照+事件）、临期黄标 `nearExpiry`（`app.mdm.cert-expiry-alert-days:30`）、`certs-verify` 核验解除（前置 CERT_EXPIRED + 存在未来有效证照，→QUALIFIED+快照+CERT_RENEWED）、`impact`（最早到期证照+受限态+PO 桩）、`options` 仅 QUALIFIED；`MdmSupplierController` 全端点 + `SecurityConfig` ADMIN；编译打包通过
- [x] 1.6 接口冒烟：建档 SUP-0001 → 改码 422 → 黑名单 HIT 422 → 名称相似 409+3条 → forceCreate 放行 → 待审核态 options 不含 → 审核通过 200（REVIEW 快照+REVIEWED 事件）→ 重复审核 422 → 驳回留痕保持 PENDING → 待审核停用 422 → 合格冻结/解冻 200 → 停用/启用（启用恢复态判定）→ 证照增改入快照 → 手改证照昨天到期+列表 sweep→CERT_EXPIRED（快照+事件）→ 临期 20 天黄标标记 → 无未来证照核验解除 422 → 增未来证照+核验解除→QUALIFIED（CERT_RENEWED）→ impact 桩与最早到期证照 → 版本对比三态 → outbox 供应商事件类型可筛 → ROLE_USER 写 403 / 未认证 401

## 2. 前端

- [x] 2.1 `api/mdm/supplier-admission.js`；`views/mdm/supplier-admission/index.vue`：列表（编码/名称/法人/税号/五色状态 tag/最早到期证照+临期黄标）+ 关键字/状态筛选 + 分页（验证：页面加载、状态与黄标展示正确）
- [x] 2.2 建档/变更弹窗（四组字段 el-divider 分组、黑名单 radio、查重确认回显、编码只读）+ 审核弹窗（通过/驳回 + 原因必填 + 409 相似/422 HIT 回显）+ 行操作按状态机显示（验证：非法态按钮不出现、校验回显正确）
- [x] 2.3 证照弹窗（子表格增改、临期红黄标、核验解除按钮仅 CERT_EXPIRED 出现）+ 影响分析抽屉（最早到期证照 + PO 桩横幅）+ 详情版本抽屉 + `router/index.js` 注册 `/m/1.4.1` + **cross-domain 事件流下拉补供应商 10 类**（验证：菜单切真实页、事件流可筛供应商类型）
- [x] 2.4 懒巡检前端验证：构造昨天到期证照 → 刷新列表即见 CERT_EXPIRED 受限态（验证：无需定时任务，页面口径正确）

## 3. 端到端验证

- [x] 3.1 浏览器全流程：建档（黑名单 HIT 阻断回显 → CLEAR 通过、查重确认）→ 审核弹窗（驳回留痕 → 通过进合格）→ options 可见 → 冻结/解冻 → 证照增改（临期黄标）→ 手改过期+刷新受限 → 核验解除恢复 → 影响分析（最早到期证照+PO 桩）→ 版本历史（CREATE/REVIEW/FREEZE/CERT_* 链）→ 事件流筛 `MDM.SUPPLIER.*` → console 无 error/warn
- [x] 3.2 权限与回归：`ROLE_USER` 五写 403、查询 200、未认证 401；回归 1.3.1/1.3.2/1.3.3 页面接口无损（outbox 客户事件不受供应商事件影响）
- [x] 3.3 测试数据清理：供应商 3 表清空；客户 4 表、物料/分类、事件表归种子态（outbox 空、1 物料启用、5 分类、法人 1）
- [x] 3.4 `openspec validate add-supplier-admission --strict --no-interactive` 通过，全部任务勾选
