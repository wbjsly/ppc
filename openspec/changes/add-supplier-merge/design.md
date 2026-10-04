# Design

## Context

见 proposal.md（Why）。现状关键点：

- 023 交付供应商五态（`PENDING/QUALIFIED/FROZEN/DISABLED/CERT_EXPIRED`）+ 证照子表 `erp_mdm_supplier_cert`（`SUPPLIER_ID` 挂靠）+ 版本快照 + `MdmSupplierServiceImpl`（含 `ALLOWED` 迁移矩阵、懒 sweep、options）
- 022 交付 `OutboxPublisher`（幂等键 `bizCode:vN`、DuplicateKey→409）与 `erp_ops_outbox`；cross-domain 事件流页面有 `EVENT_TYPES` 下拉
- 客户合并（020）已交付同类逻辑：冲突预检、MERGED 终态、改挂、双 MERGE 快照、事件——**本变更是其供应商版 + 日志表 + 回退增强**
- `SimilarityUtil`（税号/名称查重）成熟；`MdmSupplierCert` 无独立版本表（证照变更挂供应商快照）
- 菜单 `M1-4-2 合并去重 /m/1.4.2` 已在 099 种子（无需菜单迁移）

## Goals / Non-Goals

**Goals:**
- 合并/回退双事务闭环，证照改挂真实执行，日志永久保留可检索
- 六态状态机守卫不被绕过（MERGED 拒绝一切、options/sweep 排除）
- 四类迁移桩口径诚实（不造假数字）

**Non-Goals:**
- 双人复核流（降级已定）、四类单据真实迁移、应付财务确认三方匹配
- 通知单推送渠道、批量合并、客户合并改造（020 已有独立实现）

## Decisions

### D1 迁移 024（1 新表 + 供应商表 2 列）

```sql
ALTER TABLE erp_mdm_supplier
    ADD COLUMN MERGED_TO VARCHAR(32) NULL COMMENT '合并指向目标编码（STATUS=MERGED 时非空）',
    ADD COLUMN PRE_STATUS VARCHAR(16) NULL COMMENT '合并前状态（回退恢复用）';

CREATE TABLE IF NOT EXISTS erp_mdm_supplier_merge_log (
    ID PK, LOG_NO VARCHAR(32) UNIQUE(MG-NNNN),   -- 人读单号
    SOURCE_ID/SOURCE_CODE, TARGET_ID/TARGET_CODE,
    PRE_STATUS VARCHAR(16),                      -- C-4.1-11 BeforeValue
    IMPACT_SUMMARY VARCHAR(500),                 -- 证照 N 份 + 四类桩口径文本
    ORIGINAL_SUPPLIER_CODE VARCHAR(32),          -- BR-4.1-28 锚点（= SOURCE_CODE）
    MERGE_AT DATETIME, OPERATOR VARCHAR(64),
    REVERTED VARCHAR(1) DEFAULT '0',
    REVERT_AT DATETIME, REVERT_REASON VARCHAR(255), REVERT_OPERATOR VARCHAR(64),
    CREATE_BY/CREATE_DATE 等审计列             -- 无 DEL_FLAG：永久保留（C-4.1-03/11）
)
```

- **日志表不建 DEL_FLAG/VER_NO**：非业务实体、只追加（回退只 UPDATE 回填列），软删与乐观锁无意义——与 `BaseEntity` 解耦，独立 `@Data` 实体（类似 `MdmOutboxEvent` 模式）
- `LOG_NO` = `MG-%04d` 从日志表 MAX+1

### D2 六态状态机扩展（改 023 的守卫）

`ALLOWED` 矩阵加 MERGED：
- **MERGED 无出边**（回退是独立方法，不走 changeStatus）
- 一切入口拒绝 MERGED：`changeStatus`（矩阵兜底）+ `update`（显式 422「已合并终态，编码锁定」）+ `review`（已有非 PENDING 422 覆盖）+ 再次合并（校验组③）
- `options()` 已按 `STATUS=QUALIFIED` 天然排除 MERGED ✓
- 懒 sweep `findCertExpiredCandidates` SQL `STATUS IN ('QUALIFIED','FROZEN','DISABLED')` 天然不含 MERGED ✓（改挂到目标后源已无证照，双重保险）
- **需求沟通中 `STATUS` 从五态扩六态**：VARCHAR(16) 无需迁移改列

### D3 合并事务（单事务五步，同客户 020 序列）

```
merge(sourceId, targetId, reason):
  1. 校验组: 源≠目标 / 源状态≠MERGED / 目标≠MERGED / 原因≥2字
     跨法人: Objects.equals(源.legalEntityId, 目标.legalEntityId) 必须相等
             （都为 null = 集团级对集团级，允许）
  2. 证照改挂: UPDATE cert SET SUPPLIER_ID=target, VER_NO+1 WHERE SUPPLIER_ID=source
     逐条（走 wrapper 乐观锁）+ 每条前快照？→ 不：证照无独立版本表，
     改挂记录汇总写入双方 supplier 快照 diff（"证照改挂 N 份: [certNo...]"）
  3. 源: STATUS='MERGED', MERGED_TO=target.code, PRE_STATUS=原状态 (乐观锁)
  4. 日志 INSERT (LOG_NO 生成, MERGE_AT=now, OPERATOR=当前人, IMPACT_SUMMARY)
  5. 双方 MERGE 快照 + outbox MERGED (bizCode=源编码)
```

- **回退** `revert(logId, reason)`：
```
  校验: 日志存在 / REVERTED='0' / MERGE_AT >= now-30d / 原因≥2字
  ① 源: STATUS=PRE_STATUS, MERGED_TO=null (乐观锁) —— PRE_STATUS 若为 PENDING 也恢复（原样）
  ② 证照回迁 source
  ③ 日志 UPDATE 回填 REVERTED/REVERT_AT/REVERT_REASON/REVERT_OPERATOR
  ④ 双方 REVERT 快照
  ⑤ outbox MERGE_REVERTED (bizCode=源编码, verNo+1 → 幂等键自然不同于 MERGED)
```

### D4 幂等键与事件类型

- `MDM.SUPPLIER.MERGED`: key=`源编码:v{源合并后verNo}`；回退后再合并 → verNo 递增 → key 不冲突 ✓
- `MDM.SUPPLIER.MERGE_REVERTED`: key=`源编码:v{回退后verNo}` ✓
- cross-domain 页 `EVENT_TYPES` 下拉 +2（任务显式列）

### D5 候选与对比接口

```
GET /api/mdm/supplier-merges/candidates?keyword=&excludeId=   疑似重复（税号+名称≤3，排除 MERGED 与自身）
GET /api/mdm/supplier-merges/compare?sourceId=&targetId=      差异并排
    → rows: [{field, sourceValue, targetValue}]（付款条件/税号/开户行/证照最早有效期）
    + advice: "建议以合格且资质齐全方为目标" 提示文本（双状态比对生成）
POST /api/mdm/supplier-merges/merge {sourceId, targetId, reason}
GET  /api/mdm/supplier-merges?keyword=&current=&size=          日志分页（联表取源/目标名称）
POST /api/mdm/supplier-merges/revert {logId, reason}
GET  /api/mdm/supplier-merges/{logId}/impact?                  影响面（证照数+四类桩）
    → {certCount, migrations:[{type:'未清PO', count:null, stub:true, note:'采购域模块未接入，暂不迁移'}, ...]}
```

- 影响面（D5 impact）由**前端确认弹窗**调用；merge 服务端也重算一次写入 `IMPACT_SUMMARY`（不信任前端）

### D6 页面（双 Tab）

```
/m/1.4.2 views/mdm/supplier-merge/index.vue
  Tab1 合并日志: 列表(LOG_NO/源/目标/合并前状态/时间/操作人/影响面/回退状态)
       + 30 天内未回退行显示「回退」按钮 → 弹窗(原因必填+回退影响说明) → 成功刷新
       + 超期行按钮禁用+tooltip「已过回退期」
  Tab2 发起合并: 关键字搜索候选列表(行选: 设为源/设为目标)
       + 「差异对比」弹窗(并排表+方向建议+影响面桩清单) + 原因必填 → 确认执行
  行内不可在本页操作供应商状态（准入页职责）
```

### D7 与准入页的衔接

- 准入页列表显示 MERGED 态（六色 tag 追加）：**跨页面小改** `supplier-admission/index.vue` 的 `statusName/statusTag` + 行操作按 D2 隐藏（`status==='MERGED'` 时仅详情+影响分析）——tasks 列显式任务
- 准入页建新供应商替代已合并编码（人工流程，无系统联动）

## Risks / Trade-offs

- [MERGED 扩态需回归 023 全状态机] → 冒烟含：MERGED 拒绝状态/变更/再合并、options/sweep 排除、准入页展示
- [证照回迁后 verNo 竞争] → 改挂走 wrapper 乐观锁，冲突 409 重试
- [PRE_STATUS 恢复到 PENDING 的边界] → 源合并前可能就是 PENDING（未审核就被合并）——恢复原样语义正确，接受
- [日志 LOG_NO 并发 MAX+1] → 与编码同模式（低并发接受）+ UNIQUE 兜底 409
- [回退期间源被手动改动的可能] → 不存在（MERGED 拒绝一切写操作，D2 保证）
- [30 天边界时区] → `MERGE_AT` 与 now 同库时钟，按日期差 ≥30 天判定（含当天：merge 第 30 天可回退，第 31 天拒绝——闭区间）

## Migration Plan

新增 `024-supplier-merge.sql`（ALTER 2 列 + 日志表，幂等）→ 重启 DbBootstrap → marker 024 + `SHOW COLUMNS/TABLES` 验证。回滚 = 回退代码（加列无破坏，日志表留空无害）。

## Open Questions

（无 —— 六态扩展、事务序列、幂等键、页面形态、边界口径均已决策。）
