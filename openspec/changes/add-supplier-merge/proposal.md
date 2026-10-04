# Proposal

## Why

菜单 1.4.2「合并去重」指向占位页。规格流程五（FR-4.1-5-1/2/3，行 641-659）与规则 BR-4.1-25~29、BR-4.1-03、C-4.1-08/10/11 定义了供应商合并去重的完整语义：疑似重复识别与差异对比、未清业务迁移（`OriginalSupplierCode` 快照）、源编码永久锁定与合并日志、30 天回退。供应商准入（023）已交付五态状态机与证照子表，但**状态机无 MERGED 态、无合并能力**——本轮补齐合并全链路，其中资质证照改挂是唯一可真实执行的迁移（采购/财务/质量域未开发）。

## What Changes

### 功能一：合并申请与执行（FR-4.1-5-1/2/3）

- **迁移 `024-supplier-merge.sql`**：
  - `erp_mdm_supplier` 加列 `MERGED_TO VARCHAR(32)`（合并指向目标编码）、`PRE_STATUS VARCHAR(16)`（合并前状态，回退恢复用）——`STATUS` 扩第六态 **`MERGED`**（同客户域终态模式）
  - `erp_mdm_supplier_merge_log`（独立合并日志，BR-4.1-03 永久保留 + C-4.1-11 审计）：`LOG_NO`、`SOURCE_CODE`/`TARGET_CODE`、`SOURCE_ID`/`TARGET_ID`、`PRE_STATUS`、`IMPACT_SUMMARY`（影响面：证照份数 + 四类清单桩口径）、`ORIGINAL_SUPPLIER_CODE`（= 源编码，BR-4.1-28 预埋反查锚点）、`MERGE_AT`、`OPERATOR`、`REVERTED`（0/1）、`REVERT_AT`/`REVERT_REASON`、`REVERT_OPERATOR`
- **疑似重复清单**：税号精确 + USCC + 名称编辑距离 ≤3（复用 `SimilarityUtil`，C-4.1-07）；候选排除源自身与已 MERGED
- **差异对比视图**（FR-4.1-5-1 SOP）：付款条件/税号/开户行/资质有效期 逐字段并排（接口返回对比行，弹窗展示，供人工裁定）
- **合并校验组**（提交时）：跨法人 422（BR-4.1-25，源/目标 `legalEntityId` 不一致，含一侧为空一侧非空）；源=目标 422；源/目标已 MERGED 422；原因必填 + 影响面确认（降级，见偏差）
- **合并执行（单事务）**：① 证照改挂（源证照 `SUPPLIER_ID` → 目标，真实迁移，逐条入双方快照）② 源 `STATUS=MERGED` + `MERGED_TO` + `PRE_STATUS`（乐观锁，编码锁定 C-4.1-10：MERGED 态拒绝一切状态/变更操作）③ 合并日志 INSERT ④ 双方 MERGE 版本快照 ⑤ `MDM.SUPPLIER.MERGED` outbox 事件（复用 022 Publisher）
- **未结争议单据阻断（BR-4.1-26）与四类迁移清单（BR-4.1-28）** = 桩明示「采购/质量域未接入」；应付财务确认（BR-4.1-29）= 桩明示「财务域未接入」——影响面接口返回 `migrationStub` 结构（四类 `{type, count:null, stub:true, note}`）

### 功能二：30 天合并回退（行 659/707 明文）

- **回退接口**（`revert`）：读日志 → 校验 `REVERTED=0`、`MERGE_AT` 距今 ≤ 30 天（超期 422「已过回退期，须走新建+人工关联历史」）、回退原因必填
- **回退执行（单事务）**：源 `MERGED → PRE_STATUS`（恢复原状态）+ 清 `MERGED_TO`；证照改回源（`SUPPLIER_ID` 回迁）；日志回填 `REVERTED=1/REVERT_AT/REVERT_REASON/REVERT_OPERATOR`；双方 `REVERT` 版本快照；`MDM.SUPPLIER.MERGE_REVERTED` outbox 事件
- 回退后源恢复可编辑/状态操作（非终态）

### 前端 `/m/1.4.2`

- `views/mdm/supplier-merge/index.vue`：**Tab1 合并日志**（LOG_NO/源/目标/发生时间/影响面/回退状态 + 回退操作按钮 30 天内可用）+ **Tab2 发起合并**（关键字搜疑似重复候选列表 + 指定源与目标 + 差异对比弹窗 + 影响面与桩口径展示 + 原因必填确认）
- `router/index.js` 注册 `/m/1.4.2`；`SecurityConfig` 写接口 ADMIN；事件流下拉补 `MERGED/REMERGED_REVERTED` 两类型（cross-domain 页小改）

### 与规格的关系

| 规格条目 | 处理 |
|---|---|
| **FR-4.1-5-1 相似识别+差异对比+方向提示（行 643）** | **补齐**（SimilarityUtil + 对比接口 + 页面提示合格方优先） |
| **FR-4.1-5-2 资质文件迁移 + OriginalSupplierCode（行 649）** | **部分**：证照真实改挂 + 锚点字段入日志；四类单据=桩（偏差记） |
| **FR-4.1-5-3 源 Inactive+已合并标识+事件（行 655）** | **补齐**（MERGED 态 + MERGED_TO + Outbox MERGED） |
| **BR-4.1-25 跨法人阻断（行 769）** | **补齐** |
| **BR-4.1-03 / C-4.1-10 编码永久锁定+合并日志（行 686/697）** | **补齐**（MERGED 终态 + 独立日志表） |
| **C-4.1-11 审计留痕（行 698）** | **补齐**（日志表全字段） |
| **行 659/707 30 天回退** | **补齐**（回退接口 + 日志回填 + 事件） |
| BR-4.1-27/C-4.1-08 双人复核（L2） | **降级**：原因必填+影响面确认（对齐 020 客户合并决策，偏差表记） |
| BR-4.1-26 未结争议单据阻断 / BR-4.1-28 四类迁移 / BR-4.1-29 财务确认 | **桩明示**（采购/质量/财务域未开发，偏差记） |
| 《供应商合并通知单》推送渠道 | **Non-Goal**（事件桩代，偏差记） |
| BR-4.1-05 批量合并 5000 上限 | **Non-Goal**（合并为一对一，批量语义属重构类） |

**已沿基线**：快照记变更后值、乐观锁 verNo、禁硬删、写接口 ROLE_ADMIN、事件复用 022 OutboxPublisher。

## Capabilities

### New Capabilities
- `supplier-merge`: 供应商合并去重 —— 疑似重复识别与差异对比、跨法人等校验组、证照真实迁移与四类桩口径、MERGED 终态与编码锁定、独立合并日志、30 天回退、Outbox 事件

### Modified Capabilities
<!-- 无：openspec/specs/ 为空（前十五变更未归档）；supplier-admission delta 未合入，MERGED 态与合并能力为本能力新增 -->

## Impact

- **后端**：迁移 024（供应商表 +2 列 + 合并日志表）；`MdmSupplier` 加 `mergedTo/preStatus` 字段、状态枚举扩 MERGED；新 `MdmSupplierMergeService/Impl`（候选/对比/合并/回退/日志）+ `MdmSupplierMergeController`（`/api/mdm/supplier-merges`）；`MdmSupplierServiceImpl` 状态守卫补 MERGED 拒绝分支；`SecurityConfig`
- **前端**：`api/mdm/supplier-merge.js`、`views/mdm/supplier-merge/index.vue`（双 Tab）、`router/index.js` `/m/1.4.2`；cross-domain 事件流下拉 +2 类型
- **数据**：1 张新日志表 + 供应商表 2 列；证照表数据改挂（业务数据迁移）
- **依赖**：023 供应商五态/证照子表、022 OutboxPublisher、SimilarityUtil；采购/财务/质量域维持桩
