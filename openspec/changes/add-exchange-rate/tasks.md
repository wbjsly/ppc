# Tasks

## 1. 后端

- [x] 1.1 迁移 `025-exchange-rate.sql`：2 表（`erp_mdm_exchange_rate` 含序列复合索引 `(BASE_CCY,QUOTE_CCY,RATE_TYPE,EFFECTIVE_DATE)`、`erp_mdm_exchange_rate_version` ENTITY_ID+VERSION_NO 唯一）；重启后端验证 marker 025 与 2 表存在（验证：`SHOW TABLES LIKE 'erp_mdm_exchange%'`）
- [x] 1.2 实体 `MdmExchangeRate(+Version)` + DAO（序列查询按生效日排序、试算区间命中）；`MdmExchangeRateService` 接口；编译通过（验证：`mvn package` 通过）
- [x] 1.3 创建/变更校验组：必填四件（BR-4.1-16 L1）、ISO 4217 大写 3 位 + BASE≠QUOTE、`RATE>0` 6 位精度、失效日≥生效日；**序列键（BASE/QUOTE/TYPE）创建后锁定 422**；历史（失效日<今天）编辑/删除 422（BR-4.1-18）；生效中编辑原因必填入快照（验证：编译通过）
- [x] 1.4 **区间冲突与断档算法**（design D2）：`requireNoGapOrOverlap` 相交（闭区间共享端点放行）→ 右断档/左缺口 422 带可操作口径 → 首条放行 → 编辑排除自身；试算 `trial`（缺省类型 MIDDLE→BUY→SELL 回退并标明、无命中明示 reasons 非空）（验证：编译通过）
- [x] 1.5 `MdmExchangeRateController`（分页含 lifecycle 计算态筛选/CRUD/versions/diff/trial）+ 事件 CREATED/UPDATED 接入 `OutboxPublisher`（bizCode=`BASE/QUOTE:type`）+ `SecurityConfig` 写接口 ADMIN；编译打包通过
- [x] 1.6 接口冒烟：创建 200（ISO 校验通过）→ 小写/4 位币种 422 → BASE=QUOTE 422 → 缺来源编号/生效日 422 → 汇率 7.1234567→7.123457 → **同序列相交 422（含冲突清单）** → 断档 422（右缺口提示 ≤X 日）→ 左缺口 422 → 三类型独立并存 200 → 首条放行 → 变更（序列键改 422、缺原因 422、贴合衔接 200 入快照）→ 手改历史失效日 + 编辑 422 → 删除历史 422 → 试算四场景（命中含来源编号/缺失明示 reasons/缺省类型回退标明/日期区间外）→ lifecycle 筛选三态正确 → 版本对比 200 → outbox `MDM.RATE.*` 事件与幂等键格式 → ROLE_USER 写 403 / 未认证 401

## 2. 前端

- [x] 2.1 `api/mdm/exchange-rate.js`；`views/mdm/exchange-rate/index.vue`：列表（币对/类型/区间/计算态标签/汇率/来源编号）+ 币对/类型/计算态筛选 + 分页（验证：页面加载、三计算态标签正确）
- [x] 2.2 新建/变更弹窗（四字段 + 类型下拉 + 币种格式 tip + 历史只读提示）：**冲突与断档 422 清单 Alert 回显**、序列键与历史编辑禁用（前端先禁 + 后端 422 双保险）（验证：冲突清单前端回显、历史行无编辑入口）
- [x] 2.3 试算面板（币对+日期+类型可选 → 结果卡含来源编号 / 缺失明示+原因列表）+ `router/index.js` 注册 `/m/1.5.1` + **cross-domain 事件流下拉补 `MDM.RATE.CREATED/UPDATED` 两类型**（验证：菜单切真实页、四类试算场景前端复验、事件流可筛）
- [x] 2.4 历史 append-only 前端验证：构造已失效记录 → 行内编辑/删除入口消失（验证：与生效中行的操作差异对比）

## 3. 端到端验证

- [x] 3.1 浏览器全流程：建档（格式/必填阻断回显）→ 链式补第二段（贴合衔接成功）→ 构造断档阻断（冲突清单 Alert）→ 三类型独立建档 → 变更（原因必填+重跑校验）→ 历史行只读回显 → 试算面板（命中/缺失/类型回退）→ 事件流筛 `MDM.RATE.*` → console 无 error/warn
- [x] 3.2 权限与回归：`ROLE_USER` 创建/变更 403、查询与试算 200、未认证 401；回归 1.4.1/1.4.2 页面接口无损（outbox 共表客户/供应商事件不受影响）
- [x] 3.3 测试数据清理：汇率 2 表与 outbox 清空；供应商/客户/价格协议/物料/分类归种子态（outbox 空、1 物料启用、5 分类、法人 1、业务表全空）
- [x] 3.4 `openspec validate add-exchange-rate --strict --no-interactive` 通过，全部任务勾选
