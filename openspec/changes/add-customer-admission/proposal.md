# Proposal

## Why

菜单 1.3.1「客户准入」指向占位页，客户域**零代码零表**。规格流程六（FR-4.1-6-1/6-2）与规则 BR-4.1-04、BR-4.1-30/31 定义了客户主数据「集团视图 + 法人视图」两级结构的完整语义：集团建档查重与税号硬阻断、法人视图挂载与额度求和约束、合并去重。本轮一次性建起客户主数据域（含状态机与合并），为 1.3.2 信用额度、1.3.3 跨域共享及下游销售域引用提供载体。

## What Changes

- **迁移 `020-customer-admission.sql`**：`erp_mdm_customer_group`（集团视图：`CUSTOMER_CODE` CUST-NNNN 唯一、名称、税号、统一社会信用代码、信用评级、信用总额度、状态）+ `erp_mdm_customer_view`（法人视图：`GROUP_ID` 挂靠、法人主体、收货地址、联系人、电话、付款条件）+ 各自版本表 `erp_mdm_customer_group_version` / `erp_mdm_customer_view_version`（`OP_TYPE` 含 CREATE/UPDATE/MERGE/DISABLE/FREEZE）
- **编码规则**：`CUST-0001` 4 位流水（沿 `XX-NNNN` 模式），创建后不可改（C-4.1-01 精神）
- **集团视图建档**（FR-4.1-6-1）：查重 = 税号精确 + 名称编辑距离 ≤3（复用物料 C-4.1-07 模式，命中展示最近 3 条）；**税号被其它集团视图占用 → L1 硬阻断（BR-4.1-30）+ 引导走合并**
- **法人视图挂载**（FR-4.1-6-2）：挂集团下多法人各一条；保存时 **Σ法人信用额度 ≤ 集团总额度 × `GROUP_CREDIT_LIMIT_RATIO`（默认 100%）超限阻断（BR-4.1-31）**——额度字段本期建列并落校验（额度调整 UI 属 1.3.2）
- **状态机**：启用 / 停用（软删，下游 SO 引用阻断留桩）/ **冻结**（集团冻结 → 级联标记其下法人视图冻结，BR-4.1-34 语义预埋；下游 SO 阻断引用留桩 BR-4.3-08）
- **客户合并**（C-4.1-08 降级实现，见偏差）：源集团 → 目标集团，税号/名称相似清单辅助；校验（自身/已合并/税号跨主体提示）+ 原因必填 + 影响面统计（挂载法人视图数）；执行 = 源状态置 `MERGED`（编码永久锁定，禁再启用/复用，C-4.1-10）+ 法人视图改挂目标 + 双方版本快照 MERGE
- **版本快照**：全部写操作入版本链（快照记变更后新值 + 原因入 diff），详情抽屉含版本历史与逐字段对比
- **事件桩**：`MDM.CUSTOMER.CREATED/UPDATED/CREDIT_UPDATED/MERGED` TODO 日志桩
- 前端 `views/mdm/customer/`（集团列表 + 法人视图抽屉/弹窗 + 合并弹窗）、`router/index.js` 注册 `/m/1.3.1`；`SecurityConfig` 写接口 ADMIN

### 与规格的关系

| 规格条目 | 处理 |
|---|---|
| **FR-4.1-6-1 集团视图建档 + 查重（行 663-668）** | **补齐**（核心） |
| **BR-4.1-30 税号重复硬阻断（行 779）** | **补齐** |
| **FR-4.1-6-2 法人视图挂载（行 669-674）** | **补齐**（额度字段建列+求和校验预埋） |
| **BR-4.1-31 额度求和超限阻断（行 780）** | **补齐**（校验先于 1.3.2 UI 落地） |
| **BR-4.1-04 两级结构权限分层（行 687）** | 部分：字段分层建模落地；角色分层沿 ADMIN 基线（集团/法人管理员未细分角色，记偏差） |
| **BR-4.1-34 集团冻结级联（行 783）** | 部分：级联标记落地；下游 SO 阻断为桩（销售域未开发） |
| C-4.1-08 合并双人复核（行 695） | **降级**：原因必填 + 影响面确认，不建复核流（用户决策，偏差表记 L2→L1+留痕） |
| 误合并 30 天回退（行 659/707） | **Non-Goal**（偏差表记） |
| FR-4.1-6-3 额度调整、BR-4.1-32/33 | **Non-Goal**（属 1.3.2 信用额度菜单） |
| 外部征信接入、每半年复评（行 666） | **Non-Goal**（外部系统） |

**已沿基线**：无审批、立即生效、版本快照记变更后值、乐观锁 verNo、禁硬删、编码创建后不可改、写接口 ROLE_ADMIN。

## Capabilities

### New Capabilities
- `customer-admission`: 客户主数据准入 —— 集团/法人两级建档、CUST 编码、税号与名称查重、税号硬阻断、额度求和校验、状态机（停用/冻结级联）、客户合并（编码锁定）、版本快照

### Modified Capabilities
<!-- 无：openspec/specs/ 为空（前十一变更未归档），客户域为全新能力 -->

## Impact

- **后端**：新增 `entity/mdm/MdmCustomerGroup(MdmCustomerView)(+Version)`、`dao/mdm/MdmCustomerGroupDao/MdmCustomerViewDao`、`service/mdm` + `impl` 接口实现、`MdmCustomerController`（`/api/mdm/customer-groups`、`/api/mdm/customer-views`）；`MdmItemSimilarity` 类查重逻辑复用（编辑距离工具下沉或复制）
- **前端**：`api/mdm/customer.js`、`views/mdm/customer/index.vue`（集团列表 + 法人视图弹窗 + 合并弹窗 + 详情/版本抽屉）、`router/index.js` `/m/1.3.1`
- **数据**：4 张新表（`erp_mdm_customer_group` / `erp_mdm_customer_view` + 两张版本表）；菜单种子已有 M1-3-1 无需菜单迁移
- **依赖**：复用物料域的查重/版本快照/乐观锁模式；下游 SO 引用、事件总线维持 TODO 桩
