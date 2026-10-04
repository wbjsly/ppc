# Design

## Context

见 proposal.md（Why）。现状关键点：

- 客户域写操作散落两处 Service：`MdmCustomerServiceImpl`（建档/变更/停用/冻结/合并/saveView）、`MdmCreditServiceImpl`（额度调整/集团基准/复审/压缩/回滚）——共约 **10 个事件产生点**，现为 `log.info("MDM.CUSTOMER.*")` 桩
- 客户/物料均用 `BaseEntity`（ASSIGN_UUID 主键、`@Version verNo`、`@TableLogic delFlag`）；版本快照模式成熟
- 物料表 `erp_mdm_item` 有 `ITEM_CODE` 唯一索引与 `STATUS='1'` 启用过滤（价格行 SKU 参照可用 `MdmItemDao`）
- 菜单 `M1-3-3 跨域共享 /m/1.3.3` 已在 099 种子；表命名：**通用事件表属 `ops` 域**（`erp_ops_outbox`），业务数据属 `mdm` 域
- 项目无消息总线；`DbBootstrap` 迁移机制、JDK 17、MyBatis-Plus 3.5.5

## Goals / Non-Goals

**Goals:**
- 事件与主操作同事务（原子性），幂等键防重放，页面可见事件流与诚实的桩口径
- 价格协议实体化承载三类协议，试算接口为销售域预铺（优先级与阶梯匹配正确）
- outbox 表通用化（下个域接入零迁移）

**Non-Goals:**
- 消息总线投递/消费端对账（DC-19 日对账）、渠道基准价 CHANNEL 第四类
- SO 价格绑定与阻断、价格异常 ±30% 审批、协议审批流（基线无审批）
- 非客户域的事件接入（物料/信用域留待其变更）

## Decisions

### D1 表设计（迁移 022，4 张表）

```sql
erp_ops_outbox                    -- 通用事件表（ops 域）
  ID PK(uuid), EVENT_ID UNIQUE, EVENT_TYPE VARCHAR(64),
  VERSION INT, RECORD_VERSION INT, OCCURRED_AT DATETIME,
  SOURCE VARCHAR(64), IDEMPOTENCY_KEY VARCHAR(128) UNIQUE,
  LEGAL_ENTITY_ID VARCHAR(64), PAYLOAD JSON/LONGTEXT,
  STATUS VARCHAR(16) DEFAULT 'PENDING', CONSUMER_NOTE VARCHAR(255),
  CREATE_BY/CREATE_DATE/UPDATE_BY/UPDATE_DATE/DEL_FLAG/VER_NO
  -- KEY IDX_OB_TYPE(EVENT_TYPE), IDX_OB_STATUS(STATUS)

erp_mdm_price_agreement           -- 协议头（mdm 域）
  ID PK, PA_CODE UNIQUE(PA-NNNN), PA_NAME,
  AGREEMENT_TYPE VARCHAR(16),          -- EXCLUSIVE / LADDER / TIME
  CUSTOMER_GROUP_ID VARCHAR(64) NULL,  -- 挂靠二选一
  CUSTOMER_VIEW_ID VARCHAR(64) NULL,
  EFFECTIVE_DATE DATE NOT NULL, EXPIRE_DATE DATE NULL,
  STATUS VARCHAR(1) DEFAULT '0',       -- 0 未生效 / 1 生效中 / 2 过期 / 3 停用
  STOP_REASON VARCHAR(255) NULL, CHANGE_REASON VARCHAR(255),
  审计 + DEL_FLAG + VER_NO
  -- KEY IDX_PA_GROUP / IDX_PA_VIEW / IDX_PA_TYPE_STATUS

erp_mdm_price_agreement_line
  ID PK, PA_ID FK, ITEM_CODE VARCHAR(32),
  UNIT_PRICE DECIMAL(18,4) NOT NULL,
  MIN_QTY DECIMAL(18,3) NULL, MAX_QTY DECIMAL(18,3) NULL,  -- LADDER 专用
  审计 + DEL_FLAG
  -- KEY IDX_PAL_PA(PA_ID), 同 SKU 区间重叠由服务层校验

erp_mdm_price_agreement_version    -- 沿版本快照基线（ENTITY_ID+VERSION_NO 唯一）
```

- **payload 用 LONGTEXT 而非 JSON 类型**：MySQL5.7+ 虽支持 JSON，但项目快照全是 LONGTEXT，保持一致且避免驱动序列化差异
- **STATUS 用 VARCHAR(16)**（事件）与 **VARCHAR(1)**（协议，沿项目状态位惯例）——两表语义不同不强行统一
- **挂靠二选一 CHECK**：MySQL 8 支持 CHECK，但项目惯例用服务层校验（013-021 全部如此）——**服务层校验**，不加 CHECK，保持一致

### D2 事件接入：`OutboxPublisher` 组件 + 事务内调用

```java
@Component
public class OutboxPublisher {
    // 统一信封组装：event_id=UUID, occurred_at=now, source="mdm-service",
    // idempotency_key = bizCode + ":v" + recordVersion
    public void publish(String eventType, String bizCode, int recordVersion,
                        String legalEntityId, Object payload);
}
```

- **调用点**（10 处，全部已在 `@Transactional` 方法内）：
  - `MdmCustomerServiceImpl`: CREATED / UPDATED / DISABLED /（enable 与 freeze/unfreeze 归 UPDATED？—— **按 spec 场景表**：spec 只列了 CREATED/UPDATED/CREDIT_UPDATED/DISABLED/MERGED 五类在 proposal，状态类统一归 UPDATED 不够可观测 → **实际 event_type 粒度**：`CREATED/UPDATED/DISABLED/ENABLED/FROZEN/UNFROZEN/MERGED/CREDIT_UPDATED/REVIEWED/TEMP_ROLLBACK` 十类，事件流可筛选更精确；proposal 的五类是代表非穷举，design 修正记入
  - `MdmCreditServiceImpl`: CREDIT_UPDATED / REVIEWED / TEMP_ROLLBACK
- **幂等键冲突处理**：`DuplicateKeyException` → 转 `ServiceException(409, 事件重复已跳过)`？—— 不：同 `bizCode:vN` 冲突意味着**同一版本重复快照**，是 bug 信号，**向上抛 409 让主操作失败**（fail-fast 比静默丢事件安全；spec 场景「拒绝或安全跳过」二选一，选拒绝）
- **payload 内容**：变更后关键字段（复用 `snapshotJson` 的精简版：编码/状态/额度等按事件类型取子集）+ diffSummary；**不放敏感字段**（税号、联系人电话——事件可能被跨域消费，按最小披露）
- log.info 保留（日志与台账双写，spec 要求）

### D3 价格协议状态机与懒过期

```
创建: EFFECTIVE_DATE > today → 0 未生效
      EFFECTIVE_DATE <= today && (EXPIRE_DATE null || >= today) → 1 生效中
      EXPIRE_DATE < today → 直接建过期 422（拒绝建已过期协议）
编辑: 0/1 可编辑（原因必填→快照）；2/3 仅查看（422）
停用: 0/1 → 3（原因必填）；3 → 拒绝（无解停，基线无审批，停用即终态？—— 否：
      停用是误操作可恢复场景，允许 3 → 0/1 恢复？spec 未定义。
      决策：**允许 3 → 恢复到按日期推算的状态**（恢复动作原因必填），
      理由：过期才真终态，停用是人工冻结与「合并终态」不同）
懒过期: 查询（列表/试算）前 sweep：STATUS IN (0,1) && EXPIRE_DATE < today
        → 置 2 + 快照（OP_TYPE=UPDATE, diff=status→2）；幂等（条件更新）
```

### D4 试算匹配算法（纯服务层，无新查询语言）

```
trial(groupId?, viewId?, itemCode, qty, date):
  1. 解析挂靠集合：viewId → (view.groupId, viewId)；groupId → (groupId, null)
  2. 候选 = PA where (CUSTOMER_VIEW_ID = viewId OR CUSTOMER_GROUP_ID = groupId)
           && STATUS='1' && EFFECTIVE_DATE <= date && (EXPIRE_DATE null || >= date)
           && AGREEMENT_TYPE IN (EXCLUSIVE, LADDER, TIME)
     （sweep 先跑，STATUS 可信）
  3. 分组优先级：EXCLUSIVE(3) > LADDER(2) > TIME(1)；
     同组排序：法人级优先于集团级 → EFFECTIVE_DATE DESC 取第一个
  4. 按序取第一个协议，LADDER 时在行中找 MIN_QTY<=qty<=MAX_QTY 的行；
     无区间命中 → 跳过该协议继续
  5. 命中 → {paCode, type, attachLevel, unitPrice, lineId...}
     全落空 → {applicable:false, reasons:[...]} 明示原因
```

- **法人优先于集团**（spec 场景「无法人协议时用集团级」的正向：有则用）—— 归入同类型内的次级排序键
- 行缺失/单价空 = 数据异常，跳过并记 reasons（不 500）

### D5 懒过期统一入口：`CrossDomainService.sweep()`

- `sweep()` 同时做：协议懒过期（D3）；由**协议列表/试算/事件流页面加载**时前置调用（与信用额度 overview 懒校验同模式）
- **不引入新定时任务**：过期不影响正确性正确（试算的日期区间双保险已排除过期日），懒过期只是状态展示修正——与临时额度（必须清值）不同，无需 @Scheduled；design 与 spec「查询时懒回写」一致

### D6 页面与 API

```
/m/1.3.3 views/mdm/cross-domain-sharing/index.vue（双 Tab）
  Tab1 事件流: 筛选(event_type/status/keyword) + 分页列表 + payload 抽屉
         + 事件目录说明卡（生产方/消费方/幂等键规则/Schema 版本）
         + 桩横幅「消息总线未接入，PENDING 为预期态」
  Tab2 价格协议: 列表(编码/类型/挂靠/期间/状态含懒过期回写)
         + 新建/编辑弹窗（头字段 + 行编辑表格：SKU下拉/单价/阶梯区间动态列）
         + 试算面板（客户+SKU+数量+日期 → 结果卡 或 无适用协议+原因）

API: /api/mdm/outbox        GET 事件流分页+详情
     /api/mdm/price-agreements    GET列表/POST新建/PUT变更/GET详情/版本/对比
     /api/mdm/price-agreements/{id}/stop   PUT 停用/恢复（reason）
     /api/mdm/price-agreements/trial       GET 试算
写操作 → hasRole("ADMIN")（POST/PUT /api/mdm/price-agreements/** 已覆盖 stop/trial 为 GET）
```

### D7 客户域事件接入的侵入性控制

- 不改 Service 签名：在既有 10 个事务方法的成功路径末尾各加一行 `outbox.publish(...)`
- `MdmCustomerServiceImpl` 与 `MdmCreditServiceImpl` 注入 `OutboxPublisher`
- **风险**：漏点 → tasks 中列逐点核对清单（按方法名列举）

## Risks / Trade-offs

- [10 个接入点漏写] → tasks D2 清单逐点勾；冒烟按事件类型逐类断言
- [幂等键唯一冲突致主操作 409] → 设计意图（fail-fast）；键含 recordVersion 天然不同版本不冲突；同版本重复=快照 bug
- [payload 泄露敏感字段] → 事件类型→payload 字段白名单（D2），不含税号/电话/地址
- [懒过期依赖有人查询] → 试算日期区间双保险（D5），过期日协议本就不可能被试算命中，展示态修正延迟无业务风险
- [停用恢复语义 spec 未定义] → design D3 主动决策（3→按日期推算状态），记入偏差表
- [LADDER 区间边界（含/半开）] → 统一**闭区间** `MIN_QTY <= qty <= MAX_QTY`（spec 写的 ≤ 语义），服务层校验重叠用闭区间判定

## Migration Plan

新增 `022-cross-domain-sharing.sql`（4 表，`CREATE TABLE IF NOT EXISTS` + 唯一索引幂等）→ 重启 DbBootstrap → 验证 marker 022 与 4 表。回滚 = 回退代码（新表无存量依赖）；outbox 数据保留无害。

## Open Questions

（无 —— 事件粒度十类、停用可恢复、闭区间、无定时过期均已在 design 决策。）
