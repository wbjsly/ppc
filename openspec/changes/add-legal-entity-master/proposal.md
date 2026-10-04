# Change: 法人主体主数据完整生命周期（新建/变更/停用）

## Why
ERP 全系统以 `legal_entity_id` 为强制外键（全局卡控 C-0-08），但当前库内无任何法人主体主数据，下游 18 个业务域落地前必须先有该权威数据源（MDM Single Source of Truth）。菜单 1.1.1「法人主体」目前指向占位页。

## What Changes
- 新建 MDM 法人主体主数据模块（后端 CRUD + 版本快照 + 卡控校验）
- 新建表 `erp_mdm_legal_entity`、`erp_mdm_legal_entity_version`（迁移脚本 `010-mdm-legal-entity.sql`）
- 前端新增 `views/mdm/legal-entity/`（列表 + 新建/变更弹窗 + 详情抽屉 + 版本对比），菜单 `M1-1-1`（`/m/1.1.1`）从占位页切换为真实页面
- 提供法人主体下拉数据源 API，供后续下游模块引用

### 与规格的已知偏差（经需求沟通确认，2026-09-30）
| 规格条目 | 规格要求 | 本期决策 |
|---|---|---|
| S-4.1-05 组织架构调整 | 多级审批 + 时间窗口切换生效 | **无审批，保存立即生效**（无工作流引擎） |
| BR-4.1-14 / C-4.1-16 | 版本 N+1 + 未来生效日期 + 已过账凭证日期校验 | **版本快照保留**；生效日期机制与凭证日期校验**不做**（无生效日期即不适用） |
| S-4.1-05 合并 | 新增/合并/撤销 | **合并不做**（下游模块均为占位页，无真实引用可迁） |
| 批量导入（BR-4.1-19） | Excel 逐行校验 | **不做**，本期仅表单录入 |

已对齐的规格约束：C-4.1-01（编码创建后不可改）、C-4.1-03（禁止硬删，仅停用）、C-4.1-07（编码重复提示近 3 条）、BR-4.1-04 编码唯一性思路、BaseEntity 审计字段与乐观锁。

## Impact
- Affected specs: `legal-entity-management`（新增能力）
- Affected code:
  - 后端 `backend/src/main/java/com/erp/`：新增 `entity/mdm/`、`dao/mdm/`、`service/mdm/`、`service/impl/mdm/`、`controller/MdmLegalEntityController.java`
  - 迁移 `backend/src/main/resources/db/mysql/010-mdm-legal-entity.sql`
  - 前端 `frontend/src/api/mdm/legal-entity.js`、`frontend/src/views/mdm/legal-entity/index.vue`（+ 子组件）、`frontend/src/router/index.js`
- 不影响既有系统管理模块与表结构
