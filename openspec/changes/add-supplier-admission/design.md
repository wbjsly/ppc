# Design

## Context

见 proposal.md（Why）。现状关键点：

- 供应商域零代码零表；菜单 `M1-4-1 准入审核 /m/1.4.1` 已在 099 种子（无需菜单迁移）
- **可复用资产**：`SimilarityUtil`（016 建、020 切换）、`OutboxPublisher`（022，含信封/幂等/白名单）、版本快照模式、懒巡检模式（021 信用 sweep）、`BaseEntity` 乐观锁、`SecurityConfig` ADMIN 规则
- 客户域两级结构（020）与信用（021）、事件（022）刚交付——本变更是第三个主数据实体，模式全部成熟
- 表命名：`erp_mdm_supplier` / `erp_mdm_supplier_cert` / `erp_mdm_supplier_version`（mdm 域）

## Goals / Non-Goals

**Goals:**
- 建档→审核→状态→证照全链一次建对，状态机迁移矩阵清晰
- 证照到期治理用已验证的懒 sweep 模式（无新定时器）
- 事件零新代码（OutboxPublisher 直接复用）
- PO 桩口径与既有 impact 模式一致

**Non-Goals:**
- 双人复核审批流、PO 交期比对阻断（采购域 4.2）、真实黑名单库对接
- 证照附件文件上传（URL 文本字段代替）、定时巡检任务
- 合并去重（1.4.2 另立变更）、通知渠道推送（页面标代替）

## Decisions

### D1 表设计（迁移 023，3 张表）

```sql
erp_mdm_supplier
  ID PK, SUPPLIER_CODE VARCHAR(32) UNIQUE(SUP-NNNN), SUPPLIER_NAME VARCHAR(128),
  ADDRESS VARCHAR(255), CONTACT_NAME VARCHAR(64), CONTACT_PHONE VARCHAR(32),
  LEGAL_ENTITY_ID VARCHAR(64) NULL,          -- 挂法人主体（可空：集团级供应商）
  BANK_NAME VARCHAR(128), TAX_NO VARCHAR(32), PAYMENT_TERMS VARCHAR(32),
  BLACKLIST_RESULT VARCHAR(16) NOT NULL,     -- CLEAR 未命中 / HIT 命中（HIT→建档 422）
  ESG_RATING VARCHAR(16),                    -- A/B/C/D 或空
  STATUS VARCHAR(16) NOT NULL,               -- PENDING/QUALIFIED/FROZEN/DISABLED/CERT_EXPIRED
  REVIEW_REASON VARCHAR(255),                -- 最近一次审核/驳回原因
  CHANGE_REASON VARCHAR(255),
  MERGED_FLAG 不做（合并属 1.4.2）；审计+DEL_FLAG+VER_NO
  KEY IDX_MS_STATUS, IDX_MS_TAX(TAX_NO), IDX_MS_LE

erp_mdm_supplier_cert
  ID PK, SUPPLIER_ID FK, CERT_TYPE VARCHAR(32)(LICENSE/INDUSTRY/OTHER),
  CERT_NO VARCHAR(64), ISSUE_DATE DATE, EXPIRE_DATE DATE NOT NULL,
  VERIFY_NOTE VARCHAR(255)(核验说明), 审计+DEL_FLAG
  KEY IDX_MSC_SUPPLIER(SUPPLIER_ID), IDX_MSC_EXPIRE(EXPIRE_DATE)

erp_mdm_supplier_version   -- ENTITY_ID+VERSION_NO 唯一 + OP_TYPE + CHANGE_REASON
```

- **STATUS 用 VARCHAR(16) 字符串枚举**（PENDING/QUALIFIED/…）而非 `1/0/2` 数字位——五态语义字符串更自文档化；物料 `1/0/2` 与客户 `1/0/2/3` 已是历史，此处新域不强续（design 决策，偏差不入 spec 因为 spec 只约束行为）
- **`options()` 只取 QUALIFIED**（等价物料 `STATUS='1'` 正向筛选）

### D2 状态机迁移矩阵

```
PENDING(待审核)   --REVIEW 通过--> QUALIFIED
PENDING           --REVIEW 驳回--> PENDING（快照留痕驳回原因，状态不变）
QUALIFIED         --冻结--> FROZEN     --解冻--> QUALIFIED
QUALIFIED         --停用--> DISABLED   --启用--> QUALIFIED
QUALIFIED|FROZEN|DISABLED --(懒巡检,任一证照过期)--> CERT_EXPIRED   自动，幂等
CERT_EXPIRED      --(核验解除,证照已更新)--> QUALIFIED              自动恢复
PENDING           仅 REVIEW 动作可变更（停用/冻结/证照过期 均 422）
CERT_EXPIRED      停用允许吗？→ 允许 DISABLED（人工干预优先于自动态）；
                  DISABLED --启用--> 若证照仍过期 → 恢复到 CERT_EXPIRED 而非 QUALIFIED
                  （D5 恢复态判定统一函数 resolveAfterRelease()）
MERGED 态不做（1.4.2）
```

- **迁移守卫集中在 `transition(from,to)` 私有方法**：合法迁移表 `Map<String, Set<String>>`，非法 422 列出当前态与允许动作——单点维护防散落

### D3 证照到期治理：懒 sweep（复用 021 模式，无 @Scheduled）

```
sweepExpiredCerts():
  candidates = 供应商 (STATUS != CERT_EXPIRED, != PENDING) 且 EXISTS cert 且 NOT EXISTS 有效证照(> today)
  （受限 = 无任何有效证照：与 D4 解除前置 countValid>0 自洽，防解除后被 sweep 打回死循环）
  → 置 CERT_EXPIRED + 快照(CERT_EXPIRED, "证照到期自动受限：{certNo}") + outbox
  触发点：supplierPage / impact / options 查询前置（try/catch 吞掉不阻塞查询）
```
- **PENDING 不参与自动受限**（未审核供应商本就不被引用，到期语义无业务作用；审核通过时校验证照有效性）
- **审核通过时 MUST 校验**：任一证照已过期 → 422「证照已过期，不可审核通过」（防止带病进合格）——spec 场景未写但属 D2「PENDING→QUALIFIED 的合理收紧」，写入 design 不入 spec（spec 的 CERT 懒 sweep 场景已覆盖受限语义）
- 临期黄标纯展示：`expireDate <= today + CERT_EXPIRY_ALERT_DAYS` → 前端算或查询返回 `nearExpiry` 标记——**后端返回标记**（查询组装时算，参数 `app.mdm.cert-expiry-alert-days:30`）

### D4 证照核验解除（CERT_RENEWED）

```
renewCerts(supplierId, reason):
  前置：STATUS == CERT_EXPIRED 否则 422
  校验：存在 EXPIRE_DATE > today 的证照（新增或既有），否则 422「无有效证照」
  → 置 QUALIFIED + 快照(CERT_RENEWED) + outbox MDM.SUPPLIER.CERT_RENEWED
```
- 与「证照子表增改」解耦：增改只入快照不动状态；解除是显式动作（核验语义，行 805「供应商管理员核验通过后解除受限并留痕」）

### D5 恢复态判定 `resolveAfterRelease()`

`DISABLED --启用-->` 目标态 = 存在过期证照 ? CERT_EXPIRED : QUALIFIED（禁止回到 PENDING——已审核过）

### D6 事件接入（零新代码）

`OutboxPublisher.publish(eventType, bizCode, recordVersion, legalEntityId, diff)`：
- bizCode 规则：供应商级 = `SUP-CODE`；证照级 = `SUP-CODE@certId`
- 事件类型 10 类：`MDM.SUPPLIER.{CREATED,UPDATED,REVIEWED,REJECTED,FROZEN,UNFROZEN,DISABLED,ENABLED,CERT_EXPIRED,CERT_RENEWED}`（驳回 REJECTED 独立成类——审计视角可观测）
- **直接注入 `OutboxPublisher` 到 `MdmSupplierServiceImpl`**，10 个调用点全在 `@Transactional` 内
- outbox 表 `EVENT_TYPE` 无枚举约束，事件流筛选下拉需补供应商类型（前端 EVENT_TYPES 扩展——**跨页面修改**：cross-domain 页的下拉加供应商 10 类）

### D7 合规与查重

- 黑名单 `BLACKLIST_RESULT='HIT'` → 建档 422 且**变更也不允许改回 HIT 后保存**？—— 改为：建档时 HIT 阻断；变更时 HIT 同样阻断（合规前置一致）；已有 HIT 存量（通过 DB 手工造的）状态操作不受限——只拦建档/变更入口
- 查重：`SimilarityUtil.similarWithin(pool, name, 3, 3, s -> s.getSupplierName())`，池 ≤5000 全量 / 超出前缀预筛（同物料模式）；税号精确用 `findTaxNoHolder` 同款 SQL

### D8 API 与页面

```
/api/mdm/suppliers
  GET    分页（keyword/status 筛选 + 前置 sweep + nearExpiry 标记）
  POST   建档（forceCreate 同物料）
  PUT    变更（编码锁 + 原因 + HIT 阻断）
  PUT    {id}/review?result=APPROVED|REJECTED&reason=   单步审核
  PUT    {id}/status?toStatus=FROZEN|DISABLED|...&reason=  状态迁移（含解冻/启用）
  PUT    {id}/certs-verify?reason=  证照核验解除
  POST   {id}/certs / PUT certs/{certId} / DELETE certs/{certId}  证照增改
  GET    {id}/impact  影响分析（最早到期证照 + PO 桩）
  GET    {id}/versions / diff  版本
  GET    options  合格供应商下拉
写操作 → hasRole("ADMIN")

/m/1.4.1 views/mdm/supplier-admission/index.vue
  列表：编码/名称/法人/税号/状态(五色tag)/最早到期证照+临期黄标/操作
  行操作按状态机（D2）：详情 | 变更 | 审核(仅PENDING) | 冻结/解冻 | 停用/启用 | 证照
  · 建档/变更弹窗：四组字段（el-divider 分组）+ 黑名单 radio + 查重提示
  · 审核弹窗：通过/驳回 + 原因必填
  · 证照弹窗：子表格增改 + 临期红黄标 + 核验解除按钮（CERT_EXPIRED 时）
  · 影响分析抽屉：最早到期证照/受限态/PO 桩横幅
  · 详情抽屉：版本历史+对比（复用既有结构）
事件流下拉扩展供应商 10 类（cross-domain 页面小改）
```

## Risks / Trade-offs

- [五态字符串与物料/客户数字位不一致] → 新域自文档化优先，迁移守卫单点；不回改存量
- [PENDING 不参与到期 sweep] → 审核通过时校验证照有效兜底（D3），带病进合格被 422 拦
- [启用恢复态判定复杂] → D5 统一函数，禁止回 PENDING
- [黑名单仅手工结论] → 偏差已记（Non-Goal 真实库）；HIT 双入口拦截（建档+变更）
- [事件流下拉跨页面修改] → tasks 显式列 cross-domain 页面小改任务
- [证照删除语义] → 禁硬删基线：证照删除用 `@TableLogic` 软删（DEL_FLAG），仍可追溯

## Migration Plan

新增 `023-supplier-admission.sql`（3 表，IF NOT EXISTS 幂等）→ 重启 DbBootstrap → marker 023 + `SHOW TABLES` 验证。回滚 = 回退代码（新表无存量依赖）。

## Open Questions

（无 —— 状态机矩阵、懒 sweep 范围、恢复态判定、事件复用均已决策。）
