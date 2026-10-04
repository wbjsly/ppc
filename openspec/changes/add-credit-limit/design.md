# Design

## Context

见 proposal.md（Why）。现状关键点：

- 020 已建 `erp_mdm_customer_group`（`CREDIT_RATING`/`CREDIT_LIMIT_TOTAL`、状态 4 态）与 `erp_mdm_customer_view`（`CREDIT_LIMIT`、`STATUS` 3 态、`GROUP_ID`），客户域 Service/Controller/前端页面已交付
- 求和校验 `requireCreditSumWithinGroup`（BR-4.1-31）已在 `MdmCustomerServiceImpl` 私有方法，含 `app.mdm.group-credit-limit-ratio` 配置
- 版本快照机制成熟（`saveViewSnapshot(opType, diff, reason)`、`OP_TYPE` 已含 CREATE/UPDATE/DISABLE/FREEZE/MERGE）
- 前端 `views/mdm/customer/index.vue` 含法人视图弹窗（额度合计+超限红标）
- 项目无调度框架先例（Spring Boot 自带 `@Scheduled`，需确认 `@EnableScheduling`）

## Goals / Non-Goals

**Goals:**
- 额度调整（常规/临时）与到期回滚形成闭环，回滚可靠（定时+懒双保险）
- 占用率与复审超期在列表层可见（管理视角）
- 时间轴零新表（复用快照）
- 复审压缩/恢复幂等，与临时额度机制不互相覆盖

**Non-Goals:**
- SO 信用检查/冻结/预收款、应收/未清 SO 占用计算（销售财务域桩）
- BR-4.3-16 连续逾期自动下调（依赖付款数据）
- 独立调整单表/导出、审批流、复审任务推送提醒（L4 之外的通知渠道）

## Decisions

### D1 落点：并入 `MdmCustomerService` 还是新建 `MdmCreditService`？
- **选择：新建 `MdmCreditService`（独立 Controller `/api/mdm/credit-limits`）**，实体操作仍走 `MdmCustomerViewDao/GroupDao`
- **理由**：额度是独立关注点（调整/回滚/复审），并入客户 Service 会让准入实现膨胀；spec 权限条目也按「额度类写操作」成组
- 求和校验复用：将 `requireCreditSumWithinGroup` 从 `MdmCustomerServiceImpl` 提为 **package-private 静态共享**（同包 `impl.mdm`，或抽 `CreditLimitSupport` 组件注入两个 Service）——选**抽 Support 组件**（持有 viewDao/groupDao/配置，避免静态状态）

### D2 迁移 021：`erp_mdm_customer_view` 加 3 列
```sql
ALTER TABLE erp_mdm_customer_view
    ADD COLUMN TEMP_CREDIT_LIMIT DECIMAL(18,2) NULL,        -- 临时额度（BR-4.1-33）
    ADD COLUMN TEMP_EXPIRE_DATE DATE NULL,                  -- 有效期（设临时额度时必填）
    ADD COLUMN LAST_REVIEW_DATE DATE NULL;                  -- 年度复审日期（C-4.1-13 台账）
ALTER TABLE erp_mdm_customer_view ADD COLUMN REVIEW_COMPRESSED VARCHAR(1) NULL DEFAULT '0';  -- 复审压缩标记（恢复用）
```
- MySQL 无 `ADD COLUMN IF NOT EXISTS`（013 教训）→ 裸 `ADD COLUMN` + marker 幂等（marker 保证单次执行），注释写明
- **压缩列设计推演**：压缩把 `CREDIT_LIMIT` 改小后，「恢复」需要知道原值——两个选项：
  - a) 标记位 + 原值存快照（恢复时从最近一条压缩前快照读原值）—— 复杂
  - b) **压缩不改 `CREDIT_LIMIT`，改写 `TEMP_CREDIT_LIMIT`**：压缩 = 临时额度置为 `常规×(ratio-1)` 的负向… 负数语义脏
  - c) **选 b 的变体**：新增独立列 `COMPRESSED_LIMIT`（复审压缩后的生效值），有效额度 = `COALESCE(COMPRESSED_LIMIT, CREDIT_LIMIT) + 有效临时`；标记列即 `COMPRESSED_LIMIT IS NOT NULL`
  - **最终选 c**：压缩/恢复 = 写/清一个列，天然幂等且原值无损；D2 加列为 4 个（`COMPRESSED_LIMIT` 替代 `REVIEW_COMPRESSED`）

### D3 有效额度口径（试算与校验统一）
```
effectiveLimit(view) = COALESCE(COMPRESSED_LIMIT, CREDIT_LIMIT)
                     + (临时到期未回滚 ? 0 : TEMP_CREDIT_LIMIT ?? 0)
```
- **求和校验（BR-4.1-31）只看 `CREDIT_LIMIT` 常规列**——与准入现有实现一致，临时/压缩不参与求和（临时是额外授信，规格行 671 只约束「法人信用额度」）
- 占用率分母 = 集团 `CREDIT_LIMIT_TOTAL`，分子 = Σ`CREDIT_LIMIT`（常规口径，与求和约束对齐）
- 页面试算用 `effectiveLimit`（含临时与压缩），两个口径在 UI 分开展示：「常规额度（求和口径）」vs「当前有效额度（试算口径）」

### D4 到期回滚（BR-4.1-33）双机制
```
@Scheduled(fixedDelayString = "${app.mdm.credit-sweep-interval-ms:3600000}")
sweepExpiredTemp():
  SELECT ... WHERE TEMP_EXPIRE_DATE <= CURDATE() AND (TEMP_CREDIT_LIMIT IS NOT NULL)
  逐条：置 TEMP 两列 NULL → 快照 OP_TYPE=UPDATE（diff: temp→清空，reason=到期自动回滚）+ 日志桩
懒校验：creditPage/impact 查询前先跑同一 sweep（幂等，代价一次 UPDATE 扫描）
```
- `@EnableScheduling` 首次引入（加在启动类或配置类，注明）
- 无 Spring Session/分布式锁：单实例部署（本地 Docker）无竞争问题，注释记假设
- 回滚失败不阻塞查询（try/catch 记日志）

### D5 复审压缩/恢复（C-4.3-13 降级）
```
压缩触发（幂等）：LAST_REVIEW_DATE 为空 → 建档满12个月才算超期（无建档日期？
  → 用 create_date，视图表有）
  LAST_REVIEW_DATE 非空 → 距今 >12 个月超期
  超期 && COMPRESSED_LIMIT IS NULL → COMPRESSED_LIMIT = CREDIT_LIMIT × ratio（入快照）
恢复：「复审通过」按钮 → LAST_REVIEW_DATE=today, COMPRESSED_LIMIT=NULL（入快照）
```
- 触发时机：**懒触发**（查询列表时对超期且未压缩的行执行压缩）+ 不设定时任务（复审是人工事件，压缩随查询生效即可，避免与 D4 双任务交织）；spec 说「超期时自动压缩」未规定触发器——懒触发满足「打开页面即生效」的可观察行为
- **与临时额度互不覆盖**：压缩写 `COMPRESSED_LIMIT`，临时写 `TEMP_*`，D3 相加
- 「禁止新增 SO」记 Non-Goal（偏差表已有）

### D6 API 形态（`/api/mdm/credit-limits`）
```
POST   /view-limit          { viewId, creditLimit, tempLimit?, tempExpireDate?, reason }  法人额度调整
PUT    /group-limit         { groupId, creditLimitTotal?, creditRating?, reason }         集团基准调整
POST   /review              { viewId, reason }                                            复审通过（恢复）
GET    /group-occupancy?groupId=                                                          占用率
GET    /timeline?groupId=                                                                 额度时间轴（按集团聚合各视图快照）
GET    /trial?viewId=                                                                     可用额度试算（含桩口径）
```
- 写操作 ADMIN（`POST/PUT /api/mdm/credit-limits/**`）；时间轴/试算 GET authenticated
- 法人额度调整复用求和校验 → 失败 422 同文案

### D7 前端形态
```
/m/1.3.2 views/mdm/credit-limit/index.vue
  列表：客户集团行（编码/名称/评级/总额度/Σ法人/占用率(>80%黄标)/复审超期红标计数/状态）
  行操作：调整总额度 | 时间轴 | 展开法人视图子表
  法人子表：常规额度(编辑) | 临时额度+到期(编辑，到期倒计时) | 当前有效额度 | 复审日期 | 复审通过
  · 额度调整弹窗：常规/临时/有效期 + 求和即时提示 + 原因必填
  · 可用额度试算：公式 + 桩标注（应收/未清SO 未接入不计入）
  · 时间轴抽屉：复用客户详情版本列表结构，聚焦额度 diff
```
- 与 `views/mdm/customer/` 分工：customer 管建档/合并/状态（准入），credit-limit 管额度视角（本变更）；法人视图在 customer 页仍可改地址等，额度字段**建议只在本页可改**（避免双入口口径漂移）——customer 页额度输入改只读 +「额度请在信用额度菜单调整」提示

## Risks / Trade-offs

- [懒触发压缩依赖有人打开页面] → spec 未规定触发时机，选择低复杂度；定时压缩留待调度需求明确（风险记 Open Question 否决——不，这是已决：懒触发满足可观察行为）
- [@EnableScheduling 首次引入影响全局] → 仅注册调度器，无既有任务冲突；扫描失败 try/catch 不影响主流程
- [customer 页额度字段改只读 = 行为变化] → 更新 customer 页面属于本变更 scope（tasks 含回归）
- [D2 裸 ALTER 遇中断] → 013 教训：marker 记录后失败需手动补；执行后 `SHOW COLUMNS` 验证
- [双口径（求和 vs 试算）易混淆] → UI 分区标注 + design D3 固化

## Migration Plan

新增 `021-credit-limit.sql`（1 个 ALTER 4 列，幂等靠 marker）→ 重启后端触发 DbBootstrap → 验证 marker 021 与 4 列存在 + `SHOW COLUMNS`。回滚 = 回退代码（加列无破坏）。

## Open Questions

（无 —— 压缩触发时机、口径分离、Service 落点均已决策。）
