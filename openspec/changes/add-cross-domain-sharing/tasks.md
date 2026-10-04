# Tasks

## 1. 后端

- [x] 1.1 迁移 `022-cross-domain-sharing.sql`：4 表（`erp_ops_outbox` 含 `EVENT_ID`/`IDEMPOTENCY_KEY` 双唯一 + 类型/状态索引；`erp_mdm_price_agreement` 含 `PA_CODE` 唯一 + 挂靠/类型状态索引；`_line` 含 `PA_ID` 索引；`_version` ENTITY_ID+VERSION_NO 唯一）；重启后端验证 marker 022 与 4 表存在（验证：`SHOW TABLES LIKE` 清单）
- [x] 1.2 实体 `MdmOutboxEvent`/`MdmPriceAgreement(+Line,+Version)` + DAO；`OutboxPublisher` 组件（统一信封组装、`bizCode:vN` 幂等键、DuplicateKey→409 fail-fast、payload 按事件类型白名单不含敏感字段、log 双写）（验证：编译通过）
- [x] 1.3 客户域 10 个写操作点接入 outbox（design D7 清单逐点）：`MdmCustomerServiceImpl` 建档 CREATED/变更 UPDATED/停用 DISABLED/启用 ENABLED/冻结 FROZEN/解冻 UNFROZEN/合并 MERGED/saveView CREDIT_UPDATED；`MdmCreditServiceImpl` 额度 CREDIT_UPDATED/复审 REVIEWED/回滚 TEMP_ROLLBACK（验证：编译通过 + 逐类事件冒烟：各操作后 outbox 有对应行且幂等键=`编码:vN`）
- [x] 1.4 `MdmCrossDomainService/Impl`：事件流分页查询（type/status/keyword 筛选 + payload 详情 + 桩口径字段）；协议 CRUD（PA-NNNN 编码 + 挂靠二选一 422 + 行校验：SKU 启用参照、LADDER 闭区间重叠 422、非阶梯单价必填）+ 版本快照（验证：编译通过）
- [x] 1.5 协议状态机（创建按日期判定 0/1、建已过期 422、0/1 可编辑 2/3 拒编辑、停用 0/1→3 原因必填、3→按日期恢复）+ `sweep()` 懒过期（查询前置，幂等）+ 试算接口（design D4 算法：挂靠双级/EXCLUSIVE>LADDER>TIME/法人优先/阶梯闭区间/无命中明示 reasons）；`MdmCrossDomainController`（outbox + price-agreements + trial）+ `SecurityConfig` 写接口 ADMIN；编译打包通过
- [x] 1.6 接口冒烟：建档→CREATED 事件（信封字段齐+幂等键）→ 同版本重放 409 → 额度调整→CREDIT_UPDATED → 合并→MERGED → 事件流三筛选 200+payload 查看 → 协议建 PA-0001 → 改码 422 → 双挂靠/零挂靠 422 → 阶梯重叠 422 → 非阶梯缺单价 422 → 停用协议→试算不命中 → 手改 DB 过期→列表懒过期回写 → 试算四场景（优先级命中/阶梯区间/法人回退集团/无适用明示）→ 编辑过期协议 422 → 恢复 200 → ROLE_USER 写 403 / 未认证 401

## 2. 前端

- [x] 2.1 `api/mdm/cross-domain-sharing.js`；`views/mdm/cross-domain-sharing/index.vue` Tab1 事件流：筛选（event_type 下拉/状态/关键字）+ 分页列表 + payload 详情抽屉 + 事件目录说明卡 + **桩横幅「消息总线未接入，PENDING 为预期态」**（验证：页面加载、筛选与 payload 展示正确、横幅可见）
- [x] 2.2 Tab2 价格协议：列表（编码/类型/挂靠/期间/状态含懒过期）+ 新建/编辑弹窗（头字段 + 行编辑：SKU 下拉仅启用物料、阶梯类型动态显示区间列、区间重叠 422 回显）+ 停用/恢复确认（原因必填）（验证：三类校验前端回显正确）
- [x] 2.3 试算面板（客户挂靠+SKU+数量+日期 → 结果卡含优先级与挂靠级标注 / 无适用协议+原因列表）+ `router/index.js` 注册 `/m/1.3.3`（验证：菜单切真实页、四类试算场景前端复验）
- [x] 2.4 懒过期前端验证：构造昨日失效日期的生效协议 → 刷新列表即见过期态（验证：无需等定时任务——本变更无定时器，页面口径正确）

## 3. 端到端验证

- [x] 3.1 浏览器全流程：客户建档/变更/停用/合并逐类触发事件 → 事件流筛选与 payload 查看 → 桩横幅 → 事件目录卡 → 建协议（EXCLUSIVE/LADDER/TIME 各一）→ 挂靠二选一与阶梯重叠阻断回显 → 停用协议 → 试算四场景回显 → 懒过期刷新生效 → 协议版本对比 → console 无 error/warn
- [x] 3.2 权限与回归：`ROLE_USER` 协议建改/停用 403、事件流与试算查询 200、未认证 401；回归 1.3.1 客户准入（事件接入后写操作链路）与 1.3.2 信用额度页面接口无损
- [x] 3.3 测试数据清理：4 新表清空（outbox/协议/行/版本），客户 4 表 + 物料/分类归种子态（1 物料启用、0 版本、5 分类、法人 1）
- [x] 3.4 `openspec validate add-cross-domain-sharing --strict --no-interactive` 通过，全部任务勾选
