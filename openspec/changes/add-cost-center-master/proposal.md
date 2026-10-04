# Change: 成本中心主数据完整生命周期（树形，新建/变更/停用）

## Why
成本中心是财务核算（凭证行、折旧分摊、成本归集）、采购请购（BR-4.2-09 预算来源）、费用报销、资产归属、HR 人力成本分摊的**共同前置主数据**，规格多处将其列为模块前置条件（4.5/4.6 前置）。菜单 1.1.2「成本中心」目前指向占位页，且法人主体模块已落地，具备挂载条件。

## What Changes
- 新建 MDM 成本中心主数据模块（3 级树形，隶属法人主体）
- 新建表 `erp_mdm_cost_center`、`erp_mdm_cost_center_version`（迁移脚本 `011-mdm-cost-center.sql`）
- 编码规则 `CC-{类型码}-{2位流水}`：PROD/SALES/ADMIN/RD，**按类型独立计数**
- 前端新增 `views/mdm/cost-center/`（树形列表 + 新建/变更弹窗 + 详情抽屉 + 版本对比），菜单 `M1-1-2`（`/m/1.1.2`）从占位页切真实页

### 与规格的已知偏差（经需求沟通确认，2026-09-30）
| 规格条目 | 规格要求 | 本期决策 |
|---|---|---|
| S-4.1-05 组织架构调整 | 多级审批 + 时间窗口切换生效 | **无审批，保存立即生效** |
| BR-4.1-14 / C-4.1-16 | 版本 N+1 + 未来生效日期 + 已过账凭证日期校验 | **版本快照保留**；生效日期机制与凭证日期校验**不做** |
| S-4.1-05 合并 | 新增/合并/撤销 | **合并不做** |
| 4.5/4.6 前置「成本中心→科目」「成本中心→利润中心」映射 | 映射已配置 | **只做本体**，映射待科目表（4.6）落地后单独排期 |
| 撤销阻断（未结转余额，行 804） | 阻断并列出未结转科目与金额 | **预留校验桩**，待财务/库存模块落地回补 |

已对齐的规格约束：DC-06（层级不得成环 + 3 级深度上限为本约束的收紧）、C-4.1-03（禁止硬删）、C-4.1-01（编码创建后不可改）、C-4.1-07（重复提示近 3 条）、FR-4.6-5-4（默认成本中心语义 → `is_default` 字段）。

## Impact
- Affected specs: `cost-center-management`（新增能力）
- Affected code:
  - 迁移 `backend/src/main/resources/db/mysql/011-mdm-cost-center.sql`
  - 后端新增 `entity/mdm/MdmCostCenter*.java`、`dao/mdm/MdmCostCenter*Dao.java`、`service/mdm/MdmCostCenterService.java`、`service/impl/mdm/MdmCostCenterServiceImpl.java`、`controller/MdmCostCenterController.java`
  - `SecurityConfig` 追加 `/api/mdm/cost-centers` 写方法 `hasRole("ADMIN")`
  - 前端 `frontend/src/api/mdm/cost-center.js`、`frontend/src/views/mdm/cost-center/index.vue`、`router/index.js` 注册 `/m/1.1.2`
- 复用：法人主体选项接口 `/api/mdm/legal-entities/options`、法人主体模块的分层与卡控写法
