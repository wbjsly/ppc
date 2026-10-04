# Proposal

## Why

菜单 1.4.1「准入审核」指向占位页，**供应商域零代码零表**。规格对供应商准入的定义分布在：供应商准入场景（行 550，四组字段）、S-4.1-02（行 715，查重复用）、资质证照到期治理（S-4.1-10 / C-4.1-13 / 行 805 异常表）、状态机（BR-4.2-18 四态 + 行 823 前置）——但无 FR 步骤表，「准入审核」动作规格空白。本轮建起供应商主数据域（建档 + 单步审核 + 五态状态机 + 资质证照到期治理），为采购域（4.2 询价/PO 的合格供应商前置）提供载体。

## What Changes

- **迁移 `023-supplier-admission.sql`**：`erp_mdm_supplier`（`SUPPLIER_CODE` `SUP-0001` 唯一、名称/地址/联系人/电话/法人主体、开户行/税号/付款条件、黑名单筛查结果/ESG 等级、`STATUS` 五态、`REVIEW_REASON`、变更原因、乐观锁）+ `erp_mdm_supplier_cert`（证照子表：类型/编号/发证日/有效期）+ `erp_mdm_supplier_version`（`ENTITY_ID+VERSION_NO` 唯一）
- **状态机五态**：`PENDING 待审核 / QUALIFIED 合格 / FROZEN 冻结 / DISABLED 停用 / CERT_EXPIRED 证照过期`；非法迁移 422
- **建档**（行 550 四组字段）：`SUP-0001` 自动编码创建后不可改；**黑名单筛查结果命中 → 建档硬阻断 422**（合规前置）；查重复用 `SimilarityUtil`（税号精确 + 名称编辑距离 ≤3 → 最近 3 条，forceCreate+dupNote 放行，C-4.1-07）；建档即 `PENDING 待审核`
- **单步准入审核**（菜单语义落地）：`PENDING → QUALIFIED`（审核通过，原因必填）/ `PENDING → PENDING` 驳回留痕（回待审核 + 驳回原因入快照）；不建双人复核流（偏差表记 L2→L1 降级）；快照 `OP_TYPE=REVIEW`
- **资质证照子表**（S-4.1-10 载体）：多条证照（营业执照/行业认证/其它），增改入版本快照；**临期红黄标**（`CERT_EXPIRY_ALERT_DAYS`=30 天黄标，简化 30/7 分级为 30 天单参数沿规格）；**到期治理**：列表查询前置懒 sweep —— 任一证照 `有效期 < 今天` → 供应商置 `CERT_EXPIRED` 受限态（幂等入快照，行 805「到期当日置受限」）；更新证照（有效期 > 今天）+ 核验动作 → 解除受限回 `QUALIFIED` 并留痕
- **C-4.1-13 影响分析口径**：供应商详情/影响分析展示最早到期证照与是否受限；PO 交期比对阻断留桩（明示采购域未接入）
- **下游 PO 阻断**（BR-4.2-18）= 桩明示（采购域未开发），`options()` 合格供应商下拉仅 `QUALIFIED`
- **事件接入复用 022 `OutboxPublisher`**：`MDM.SUPPLIER.{CREATED,UPDATED,REVIEWED,FROZEN,UNFROZEN,DISABLED,ENABLED,CERT_EXPIRED,CERT_RENEWED}` 十类写入 outbox（同事务+幂等键），事件流页面自然可见
- **版本快照**：建档/变更/审核/状态/证照增改全入快照（OP_TYPE 含 CREATE/UPDATE/REVIEW/FREEZE/DISABLE/CERT_*）；事件桩即 Outbox
- 前端 `views/mdm/supplier-admission/`（列表 + 建档/变更弹窗四组字段 + 审核操作 + 证照子表格 + 影响分析/详情抽屉）、`router/index.js` 注册 `/m/1.4.1`；`SecurityConfig` 写接口 ADMIN

### 与规格的关系

| 规格条目 | 处理 |
|---|---|
| **行 550 供应商准入场景（四组字段）** | **补齐**（核心） |
| **S-4.1-02 查重复用（行 715）** | **补齐**（SimilarityUtil 复用） |
| **S-4.1-10 资质证照到期治理（行 723）+ C-4.1-13 + 行 805** | **补齐**（子表+临期预警+到期受限+更新解除留痕） |
| **BR-4.2-18 四态状态机（行 1226）** | **部分**：五态状态机与语义落地；PO 创建/过账阻断=桩（采购域，偏差记） |
| 行 542 创建/审批/发布三权分立 | **降级**：单步审核通过（不建双人复核流，偏差表记 L2→L1） |
| S-4.1-10「推送证照更新提醒」通知渠道 | **降级**：页面红黄标 + 受限态展示（无消息通道，偏差记） |
| PO 交期 < 证照有效期阻断（C-4.1-13 主判定） | **Non-Goal**（采购域 4.2，影响分析口径预铺，偏差记） |
| 真实黑名单库/征信对接 | **Non-Goal**（手工筛查结论字段） |
| 合并去重（FR-4.1-5、BR-4.1-25~29） | **Non-Goal**（属 1.4.2 菜单，另立变更） |

**已沿基线**：无审批、快照记变更后值、乐观锁 verNo、禁硬删、编码创建后不可改、写接口 ROLE_ADMIN、复用 022 Outbox/023 前的查重与懒巡检模式。

## Capabilities

### New Capabilities
- `supplier-admission`: 供应商准入审核 —— SUP 编码建档四组字段、黑名单硬阻断、查重复用、单步审核状态机（五态）、资质证照子表与到期治理（临期预警/到期受限/更新解除）、影响分析桩口径、Outbox 事件接入

### Modified Capabilities
<!-- 无：openspec/specs/ 为空（前十四变更未归档），供应商域为全新能力 -->

## Impact

- **后端**：迁移 023（3 表）；新实体 `MdmSupplier(+Cert,+Version)` + DAO；`MdmSupplierService/Impl`（建档/审核/状态机/证照/懒 sweep/影响分析/合格 options）；`MdmSupplierController`（`/api/mdm/suppliers`）；事件复用 `OutboxPublisher`（不改其代码）；`SecurityConfig`
- **前端**：`api/mdm/supplier-admission.js`、`views/mdm/supplier-admission/index.vue`、`router/index.js` `/m/1.4.1`
- **数据**：3 张新表；客户/物料/事件域不动（outbox 供应商事件共用 022 表）
- **依赖**：022 OutboxPublisher、SimilarityUtil（016/022 已有）；采购域阻断与黑名单库维持桩
