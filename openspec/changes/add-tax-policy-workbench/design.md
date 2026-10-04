# Design

## Context

- `add-tax-code` 已交付：`MdmTaxCodeService`（create/update 行级语义、区间 `IntervalRules`、快照+`MDM.TAXCODE.*` 事件）、`MdmTaxPolicyService`（台账 CRUD、回链，编辑仅 log）、政策页（列表/弹窗/关联抽屉、ADMIN 门控）、SecurityConfig `POST/PUT/DELETE /api/mdm/tax-policies/**` 已配 ADMIN（`**` 含子路径，workbench POST 覆盖核对点）
- 快照表形态参照 026 的 `erp_mdm_tax_code_version`；事件经 `OutboxPublisher`（幂等键 `bizCode:vN`，DuplicateKey→409）
- 规格约束：S-4.1-07 切换无缝衔接、BR-4.1-17 链规则、BR-4.1-18 历史不重算、L458 全主数据快照基线

## Goals / Non-Goals

**Goals:**
- 政策域补齐快照/事件/对比，达到与税码/汇率同款基线
- 工作台把「政策 → 税码批量切换」落成可预检、行级提交、可报告的闭环，历史（已失效区间）零改动

**Non-Goals:**
- 不回填存量政策快照；不给政策编辑加必填原因（偏差表已记）
- 不新增菜单；不改 1.6.1/1.6.3 页面
- 不做工作台内调整适用范围/计税方式/税率类型（继承原记录）

## Decisions

### D1 迁移 027：政策版本表（单表，无列变更）
`erp_mdm_tax_policy_version`（ID/ENTITY_ID/VERSION_NO 唯一/SNAPSHOT_JSON/DIFF_SUMMARY/OP_TYPE/CHANGE_REASON/CREATE_BY/CREATE_DATE），快照字段 = `policyNo,policyName,issuer,issueDate,effectiveDate,summary,remark` 七件。幂等键 = `政策文号:{ID前8位}:vN`，N=快照 VERSION_NO（create 后 verNo+1 与 count+1 恒等，同税码口径）。存量政策首快照由上线后首次写生成（V1，opType 按该次操作为 UPDATE——不补造 CREATE）。

### D2 快照/事件/对比挂入既有政策 Service
`MdmTaxPolicyServiceImpl`：create/update 内 `saveSnapshot`+`outbox.publish`（与税码同构的 `buildDiff/compareSnapshots/parse/toJson` 私有工具，从 `MdmTaxCodeServiceImpl` 复制——两实体字段不同，复制比抽泛化基类简单，量级 ~80 行）；新增 `versions(id)`/`diff(id,from,to)` 接口与政策页对比抽屉（样式复用税码页 `.val-old/.val-new`）。
备选：抽 `SnapshotSupport` 共享组件——第三次出现才值得抽象（现两处字段集不同），暂复制，风险=diff 逻辑漂移（已固化的 compareSnapshots 行为一致，回归用同场景断言）。

### D3 工作台五类计划（preview/submit 同一核心）
新 `MdmTaxWorkbenchService`（挂 `/api/mdm/tax-policies/{id}/workbench`）：
- `candidates(policyId, keyword)`：`taxDao` 全量非软删 → 按税码分组取链尾（max effectiveDate 段）+ lifecycle + 建议生效日=尾+1；返回含 hint 空态
- `preview(policyId, rows, switchDate, expireDate)`（统一参数作默认，行内值优先）：每行 `taxCode` 分组取链（`selectSequence`），按 D3 五类判定生成 `plan`；**切换日规范化 = max(行内生效日||统一切换日, today)**？不——spec 要求切换日<今天**阻断**而非抬升（用户可见的口径），故直接判 `< today → 422 行失败`
- 计划判定顺序：① 链空→CREATE 首段（expire=行内）② date=尾+1→CREATE 衔接段 ③ date 落**未失效尾段** [s.eff,s.exp] 内（s.exp≥today）→SHORTEN(s→date-1)+CREATE(date..行内expire) ④ date 落中间未失效段（其后有 next）→SHORTEN+CREATE(date..next.eff-1 自动，忽略行内 expire 并回显 appliedExpire）⑤ 其他→失败（落入已失效段/早于链首/断档空洞/早于今天，各给可操作口径：应为 尾+1 或 未失效段内）
- 行内新段字段：`taxCode/newRate/effectiveDate/expireDate` 用户供；`scope/calcType/rateKind` **从被选段继承**（4 里取被缩短段，1/2/3 取链尾段）；`policyNo` = 所选政策
- 字段校验复用 `TaxCodeRules.validateFields`（新段组装后整体过）+ 区间判定（preview 模拟缩短：链中该段 expire 替换为 switchDate-1 后再判）
- **D3 实施修订**：原拟复用 `IntervalRules.check`，实测其语义为「贴全局链首/链尾」，中间段插入（类型 4）两头不贴必然误判 → 在 `IntervalRules` 新增 `checkAdjacent`（不相交 + 左贴尾 + 右贴头，面向链内插入），工作台三处判定统一改用；`check` 保持不动（单条与批量路径继续使用），算法仍单点收口于 IntervalRules
- **D4 实施修订**：① 缩短动作不能走 `MdmTaxCodeService.update`（其区间复验对收缩中的中段会误判左缺口）→ 新增专用 `shortenSegment(id, newExpire, reason)`（仅校验非历史/收缩合法性/乐观锁，快照+TAXCODE.UPDATED 照发）；② 新建动作不能走 `MdmTaxCodeService.create`（其内置 `check` 为「贴全局链首/链尾」语义，会拒绝链内中间插入——补洞与类型 4 的新建必失败）→ 新增 `createSegment(seg)`（同 create 其余职责，跳过区间复验，计划侧 `checkAdjacent` 已保证）。两能力均为工作台专用，单条/批量路径不受影响（链连续时中间插入不可达，原语义正确）

### D4 提交：行级独立事务 + 行内顺序（先缩短后新建）
`submit` 方法**不加 @Transactional**（沿批量模式）：逐行执行 plan → 每 action 独立调 `taxService.update/create`（各自事务/快照/事件）；**缩短原因自动生成** = `政策切换缩短，依据 {政策文号}`；新建 create 全字段带 policyNo。
行内失败语义：短缩成功、新建失败 → `PARTIAL`（报告单独计数入 failed 语义？spec：result=PARTIAL 单列，succeeded 只算全成功，failed 含 FAILED+PARTIAL 并标原因「缩短已完成，新建失败…可重试」）——重试安全：再次提交该行时计划重算（缩短已完成 → date 变为尾+1 → 纯 CREATE 型），天然幂等演进。
提交端**复用 preview 核心**复验（预检失败行不执行）；上限 500。
备选：整行两动作包一个事务（缩短+新建同成同败）——更简单且 PARTIAL 消失；但 create/update 各自 @Transactional 嵌套需传播调整（REQUIRES_NEW），动作粒度与「部分失败可重试」的 spec 场景冲突。**按 spec 走非整行事务 + PARTIAL**。

### D5 前端：政策行两入口 + 工作台抽屉四步
`tax-policy/index.vue`：行加「对比」（isAdmin 无关，只读）与「发起变更」（v-if isAdmin）；对比抽屉复用税码样式；工作台 `el-drawer size=90%` 内 `el-steps` 四步：
① 勾选税码（候选表+搜索+全选，显示链尾与建议切换日）→ ② 参数（统一切换日/统一新段失效日 + 行内可改列）→ ③ 预检表格（红绿、plan 动作摘要逐行、自动失效日回显）→ ④ 提交+报告（SUCCESS/FAILED/PARTIAL 统计 + 明细 + 完成后「查看关联变更」）
新 API：`api/mdm/tax-policy.js` 加 versions/diff/candidates/workbenchPreview/workbenchSubmit。

### D6 权限核对
SecurityConfig 现有 `POST /api/mdm/tax-policies/**` 通配 `**` 覆盖 `/tax-policies/{id}/workbench/preview|submit`（apply 时 curl 403 实测确认，若不覆盖则补显式规则）；versions/candidates/diff 为 GET 走 authenticated。前端「发起变更」按钮 isAdmin 门控（沿三页既有模式）。

## Risks / Trade-offs

- [缩短动作可重入/并发（两人同时发起同税码变更）] → update 乐观锁 verNo 失配 409，行级报告体现，重试即可
- [PARTIAL 行「新建失败但已缩短」的中间态] → 链仍紧凑无断档（缩短仅收缩未失效尾），spec 已定重试语义（计划重算降级为纯 CREATE）
- [preview 与 submit 计划漂移（两次调用间链变化）] → submit 复验重算计划，不复用 preview 返回值
- [复制 diff 工具两份] → D2 已记；用同断言回归兜底
- [027 中断残留] → 仅 CREATE TABLE IF NOT EXISTS 幂等，无裸 ALTER

## Migration Plan

迁移 027 追加末尾编号；部署即生效；回滚 = 还原政策页与后端新增方法，版本表留存无害。

## Open Questions

（无——范围、五类计划、切换日≥今天、PARTIAL 语义、快照不回填均已经需求确认或在 spec 场景固化）
