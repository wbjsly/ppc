# Proposal

## Why

菜单 1.1.3「利润中心」目前指向占位页；且 4.6 财务域的前置条件「成本中心与利润中心映射关系已配置」（00-erp-spec.md 行 2914）无法满足 —— 利润中心本体不存在，凭证行（FR-4.6-1-3）引用的 `profit_center_id` 也无处挂载。成本中心那轮因「科目表未落地」推迟了这组映射，但该映射的两端（成本中心表 + 即将新建的利润中心表）实际不依赖任何未落地模块，推迟理由不成立。

## What Changes

- 新建 MDM 利润中心主数据模块（**平铺单级**，隶属法人主体，编码 `PC-0001` 纯流水跨主体全局唯一）
- 新建表 `erp_mdm_profit_center` + `erp_mdm_profit_center_version`（迁移 `012-mdm-profit-center.sql`）
- **成本中心表改造**：`ADD COLUMN profit_center_id`（迁移 `013`），存量 `CC-PROD-01` 回填至种子利润中心
- 归属校验：成本中心的利润中心**须同属一个法人主体**；**停用利润中心时**若名下仍有启用中的成本中心 → 阻断并列出
- 前端新增 `views/mdm/profit-center/`，菜单 `M1-1-3`（`/m/1.1.3`）从占位页切真实页；成本中心新建/变更表单增加「利润中心」下拉（按主体过滤）
- `SecurityConfig`：`/api/mdm/profit-centers` 写方法 `hasRole("ADMIN")`

### 与规格的已知偏差（沿前两轮口径，2026-10-01 需求沟通确认）

| 规格条目 | 规格要求 | 本轮决策 |
|---|---|---|
| 4.1 MDM 治理模式（集中管控、审批发布） | 审批流 | **无审批，保存立即生效** |
| BR-4.1-14 / C-4.1-16 | 未来生效日期 + 已过账凭证日期校验 | **不做**（版本快照保留） |
| S-4.1-05 组织架构调整 | 合并能力 | **不做** |
| 撤销余额阻断（行 804） | 仅针对成本中心语境，利润中心无对应条款 | 沿用**校验桩模式**，待 4.6 落地回补凭证行引用查询 |

**已对齐/补齐的规格约束**：4.6 前置「成本中心与利润中心映射关系已配置」（本轮补齐）、C-4.1-03 禁硬删、C-4.1-01 编码创建后不可改、C-4.1-07 查重提示近 3 条。

**注**：规格对利润中心仅 3 处命中（行 453 能力授权一句话、行 2914 前置、行 2945 凭证行引用），无场景/规则/卡控 —— 平铺单级、`PC-0001` 编码格式为需求沟通中的推导决策，非规格原文。

## Capabilities

### New Capabilities
- `profit-center-management`: 利润中心主数据本体（列表/新建/变更/停用/详情/版本对比/下游选项）+ 成本中心归属利润中心的关系与校验（同主体、停用阻断、供凭证行引用）

### Modified Capabilities
<!-- 无：openspec/specs/ 当前为空（前两个变更未归档），成本中心变更的行为需求并入上述新能力的 delta -->

## Impact

- Affected specs: `profit-center-management`（新增）
- Affected code:
  - 迁移 `backend/src/main/resources/db/mysql/012-mdm-profit-center.sql`（新表 + 种子 `PC-0001`）、`013-cost-center-profit-center.sql`（成本中心加列 + 回填）
  - 后端新增 `entity/mdm/MdmProfitCenter*.java`、`dao/mdm/MdmProfitCenter*Dao.java`、`service/mdm/MdmProfitCenterService.java`、`service/impl/mdm/MdmProfitCenterServiceImpl.java`、`controller/MdmProfitCenterController.java`
  - 改造：`MdmCostCenter` 实体（加字段）、`MdmCostCenterServiceImpl`（归属校验 + 停用/利润中心联动）、`MdmCostCenterController`、`SecurityConfig`
  - 前端 `frontend/src/api/mdm/profit-center.js`、`frontend/src/views/mdm/profit-center/index.vue`、`views/mdm/cost-center/index.vue`（表单加下拉）、`router/index.js` 注册 `/m/1.1.3`
- 复用：法人主体选项接口、成本中心模块的分层与卡控写法、版本快照模式
