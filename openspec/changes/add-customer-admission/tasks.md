# Tasks

## 1. 后端数据层与业务层

- [x] 1.1 迁移 `020-customer-admission.sql`：4 张表（`erp_mdm_customer_group` 含 `CUSTOMER_CODE` 唯一索引/`TAX_NO` 普通索引/`STATUS` 4 态/`MERGED_TO`、`erp_mdm_customer_view` 含 `GROUP_ID` 索引、两张版本表 `ENTITY_ID+VERSION_NO` 唯一）；重启后端验证 marker 020 与 4 表存在（验证：`SHOW TABLES LIKE 'erp_mdm_customer%'` 返回 4）
- [x] 1.2 实体 `MdmCustomerGroup`/`MdmCustomerView`（+2 个 Version 实体）+ DAO；`common/SimilarityUtil` 抽取（Levenshtein + 相似候选），`MdmItemServiceImpl` 调用点切换并编译（验证：`mvn -DskipTests package` 通过，物料查重冒烟 200）
- [x] 1.3 Service 集团侧：建档（CUST-NNNN 自动生成 + 税号硬阻断 422 + 名称相似 409+最近3条+forceCreate/dupNote 放行）、变更（编码锁定 422 + 原因 + UPDATE 快照）、分页/详情/版本/对比（验证：编译通过）
- [x] 1.4 Service 法人侧 + 状态机：法人视图增改（`LEGAL_ENTITY_ID` 参照启用校验、同集团同法人唯一 422、**额度求和 ×ratio 超限 422**（总额度空放行+提示））；集团 停用/启用/冻结/解冻（冻结级联法人 `2`、解冻只 `2→1`、已合并终态 422、停用影响面桩 `downstreamStub`）；`impact` 端点（验证：编译通过）
- [x] 1.5 合并：相似候选查询 + `merge(sourceId, targetId, reason)` 单事务（校验 → 法人改挂 → 源 `STATUS='3'+MERGED_TO` → 双方 MERGE 快照 → `MDM.CUSTOMER.MERGED` 日志桩）；`MdmCustomerController`（groups/views CRUD + freeze/unfreeze/merge/merge-candidates/impact/versions/diff）+ `SecurityConfig` 写接口 ADMIN；编译打包通过
- [x] 1.6 接口冒烟：建档 200（编码 CUST-0001）→ 同税号 422（含引导文案）→ 名称相似 409+3条 → forceCreate 放行 → 改编码 422 → 法人挂载 200 → 同法人重复 422 → 额度超限 422 → 总额度空放行 → 冻结 200+法人联动 2 → 解冻恢复 → 停用 200+impact 桩标记 → 合并候选 200 → 合并 200（改挂+源终态+双快照）→ 已合并启用 422 → 版本对比 200 → ROLE_USER 写 403 / 未认证 401

## 2. 前端

- [x] 2.1 `api/mdm/customer.js`；`views/mdm/customer/index.vue`：集团列表（编码/名称/税号/评级/总额度/法人数/状态）+ 关键字/状态筛选 + 分页 + 详情抽屉（版本历史 + 逐字段对比）（验证：页面加载、筛选与详情可用）
- [x] 2.2 建档/变更弹窗（CUST 自动编码只读、税号硬阻断回显、名称相似确认+差异说明）+ 法人视图弹窗（子表格增改、**额度求和实时提示**、冻结联动标注）（验证：三类校验前端回显正确）
- [x] 2.3 状态操作（停用/启用/冻结/解冻确认框、已合并行终态锁定）+ 合并弹窗（相似候选搜索、影响面「源客户下 N 个法人视图」、原因必填）+ `router/index.js` 注册 `/m/1.3.1`（验证：菜单切真实页，全部操作可用）

## 3. 端到端验证

- [x] 3.1 浏览器全流程：新建集团（查重确认路径）→ 同税号阻断回显 → 变更（编码锁定回显）→ 挂法人视图（额度超限红字 → 调整通过）→ 冻结级联（法人数列/状态联动）→ 解冻 → 停用（impact 桩文案）→ 合并（候选+影响面+原因必填 → 源终态锁定、法人改挂、双侧 MERGE 快照）→ 详情版本对比 → console 无 error/warn
- [x] 3.2 权限与回归：`ROLE_USER` 建档/合并/冻结 403、查询 200、未认证 401；回归 1.2.1 物料查重（SimilarityUtil 抽取后）与 1.2.2/1.2.3 页面接口无损
- [x] 3.3 测试数据清理：客户 4 表清空（含版本），物料域归种子态（1 物料 + 0 版本 + 5 分类）
- [x] 3.4 `openspec validate add-customer-admission --strict --no-interactive` 通过，全部任务勾选
