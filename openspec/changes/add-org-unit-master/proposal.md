# Proposal

## Why

菜单 1.1.4「组织单元」目前指向占位页。组织单元是规格**全局卡控 C-0-08 的交易单据四要素之一**（法人主体 + 组织单元 + 交易币种 + 记账币种，缺失 L1 阻断过账，覆盖 4.6/5.5/5.6/5.7/5.8），同时是 5.6 行级权限的过滤维度（「仅本组织单元 + 脱敏」）、5.6 建号与 EDI 校验的有效性前置（行 8211/7976）—— 数据源不存在则这些下游全部无法开工。前三个组织管理模块（法人主体/成本中心/利润中心）已落地，组织单元是组织架构分支的最后一块本体。

## What Changes

- 新建 MDM 组织单元主数据模块（**树形 3 级**，隶属法人主体）
- **六类类型枚举**（用户 2026-10-01 确认）：工厂 FACTORY / 仓库 WAREHOUSE / 采购组织 PROC / 销售组织 SALE / 门店 STORE / 研发中心 RD
- 编码 `OU-{类型码}-{2位流水}`，**按类型独立计数**（同成本中心 `CC-PROD-01` 模式），跨主体全局唯一
- 新建表 `erp_mdm_org_unit` + `erp_mdm_org_unit_version`（迁移 `014-mdm-org-unit.sql`）
- 树形三件套：`PARENT_ID` + `TREE_LEVEL`，DC-06 成环回溯、深度 ≤3 阻断、停用父节点须先停子孙（列出清单）—— 复用成本中心已验证实现
- 下游选项接口（按主体过滤、仅启用），供 C-0-08 四要素与行级权限未来引用；交易单据真实引用校验留桩（TODO，交易域落地时回补）
- 前端 `views/mdm/org-unit/`，菜单 `M1-1-4`（`/m/1.1.4`）从占位页切真实页
- `SecurityConfig`：`/api/mdm/org-units` 写方法 `hasRole("ADMIN")`

### 与规格的已知偏差（沿前三轮口径，2026-10-01 需求沟通确认）

| 规格条目 | 规格要求 | 本轮决策 |
|---|---|---|
| 4.1 MDM 治理模式（集中管控、审批发布） | 审批流 | **无审批，保存立即生效** |
| BR-4.1-14 / C-4.1-16 | 未来生效日期 + 已过账凭证日期校验 | **不做**（版本快照保留） |
| S-4.1-05 组织架构调整 | 合并能力 | **不做** |
| **S-4.1-08 多工厂物料视图扩展** | 工厂型组织单元挂物料本厂视图（MRP 参数/采购类型/存储地点） | **Non-Goal**：物料模块（4.1 物料管理）未落地，工厂类型仅作为组织单元属性存在 |
| **5.6 行级权限按组织单元过滤** | OU 维度 SQL WHERE 注入 | **Non-Goal**：属 5.6 权限域，本期仅提供选项接口作为数据源 |

**已对齐/补齐的规格约束**：C-0-08 组织单元数据源（本轮补齐）、DC-06 层级不得成环、C-4.1-03 禁硬删、C-4.1-01 编码创建后不可改、行 8211 组织单元有效性（选项接口仅启用项）。

**注**：类型六类为用户拍板（规格仅点名工厂、仓库，示例出现 OU-PROC-01 行 8159）；「类型不锁层级」为推定（允许工厂 1 级下挂仓库 2 级等异构子节点），需求沟通中用户未提出异议。

## Capabilities

### New Capabilities
- `org-unit-management`: 组织单元主数据本体（3 级树形 CRUD/停用/版本对比/下游选项）+ 类型枚举 + 同主体与树形校验，作为 C-0-08 交易四要素与行级权限的权威数据源

### Modified Capabilities
<!-- 无：openspec/specs/ 当前为空（前四变更未归档） -->

## Impact

- Affected specs: `org-unit-management`（新增）
- Affected code:
  - 迁移 `backend/src/main/resources/db/mysql/014-mdm-org-unit.sql`（两表 + 六类示例种子）
  - 后端新增 `entity/mdm/MdmOrgUnit*.java`、`dao/mdm/MdmOrgUnit*Dao.java`、`service/mdm/MdmOrgUnitService.java`、`service/impl/mdm/MdmOrgUnitServiceImpl.java`、`controller/MdmOrgUnitController.java`
  - `SecurityConfig` 追加 org-units 写权限
  - 前端 `frontend/src/api/mdm/org-unit.js`、`frontend/src/views/mdm/org-unit/index.vue`、`router/index.js` 注册 `/m/1.1.4`
- 复用：成本中心树形实现（成环/深度/子孙停用/编码流水/版本快照）、法人主体与利润中心的分层写法、选项接口模式
