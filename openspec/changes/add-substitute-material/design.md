# Design

## Context

替代关系存储在既有 `erp_mdm_item.ALT_ITEM_CODE` 单值列（`add-item-master` 引入、无校验）。本轮补行 477 参照 gap 并建 1.2.5 集中视图。动机与补强偏差见 proposal.md。约束同前：JDK 17 + Spring Boot 2.7.18 + MyBatis-Plus、`erp_mdm_` 前缀、`DbBootstrap`、基线（无审批/立即生效/版本快照/乐观锁）。

## Goals / Non-Goals

- Goals：替代关系列表与维护、三项校验（已发布/非自身/间接环）、替代目标停用阻断、表单 gap 补齐、停用提示
- Non-Goals：链式深度业务限制、一对多替代、替代链式传播（A 停用后 B 是否自动顶上等流转逻辑）、反向同步失效清理

## Decisions

**1. 不建关系表，沿用单值列**：替代是物料属性（1:1），非独立实体；列表 = `alt_item_code IS NOT NULL` 查询 + 按需 join 目标物料。维护操作 = 物料 update 的特化路径（复用 `MdmItemServiceImpl.update` 的快照/乐观锁）。
- 备选：`erp_mdm_item_substitute` 关系表 → 拒绝，一对多与链式均无需求（Non-Goal），单列可承载且已有数据。

**2. 间接环校验 = 迭代回溯而非递归**：`setSubstitute(sourceId, targetId)` → 从 `targetId` 沿 `altItemCode` 循环取节点（`while` + `Set<已访问>`，**visited 集合天然去重 + 防脏数据死循环**），深度上限 10 超限也阻断（视为脏数据）；命中 `sourceId` → 422 + 组装链路字符串（`B → C → A`）。
- visited 判重比深度计数更本质：脏数据中的既有环（与本次无关）在第 2 次访问重复节点时即可判定「链路异常」阻断，不需要等满 10 层。
- 备选：递归 + 深度参数 → 拒绝，迭代更直白且无栈风险。

**3. 三项校验集中在 `MdmItemServiceImpl` 的私有方法 `requireSubstituteValid(sourceId, substituteCode)`**：物料表单提交（create/update）与关系维护接口共用同一入口，杜绝两处逻辑漂移；`sourceId` 在新建时为空（尚无 id）→ 新建场景退化为「目标存在 + 启用 + 目标 != 目标自己」，环校验对新建无意义（本物料尚不存在于任何链上）→ 仅在 update 与设置替代时启用环校验。
- 「非自身」在新建时无从比较（本物料编码未定），实际由设置替代/update 场景覆盖。

**4. 替代目标停用阻断**：`disable()` 扩展 —— 查 `ALT_ITEM_CODE = 待停物料编码 AND STATUS='1' AND ID != 自身`，非空 → 409 + `LIMIT 10` 清单 + 总数。
- 按编码指向（列存编码非 id），引用方查询用编码匹配；物料编码不可改（BR-4.1-13）保证该 join 稳定 —— 这正是存编码列在本轮的意外收益。

**5. 关系维护接口**：`PUT /api/mdm/items/{id}/substitute?substituteCode=xxx`（设置）+ `DELETE /api/mdm/items/{id}/substitute`（清除），走物料版本快照（与 update 同 `changeReason` 机制：本操作自动填 `changeReason=设置替代/清除替代`，不要求用户输入 —— 操作本身语义完整）。
- 备选：走通用 `PUT /items` 带 reason → 拒绝，设置替代是专用动作，语义参数化更清晰且避免前端组装全量物料字段。

**6. 列表页筛选**：`keyword`（源/目标编码名称模糊）+ `direction`（source/target 指定反查维度）+ `status`；后端单 SQL join 自身（`erp_mdm_item a LEFT JOIN erp_mdm_item b ON b.ITEM_CODE = a.ALT_ITEM_CODE`），MyBatis-Plus 不便表达 → 用 `@Select` 原生 SQL + 手工分页（COUNT + LIMIT，数据量小）。

## Risks / Trade-offs

- 存编码列的既有脏数据（不存在/停用/自身的目标）：设置新替代时即校验，存量脏行在列表中显示为「目标已失效」标记，不主动清洗（无批量修复需求）
- 停用阻断基于编码匹配，若历史脏数据指向已删除目标（物理删除不存在于本系统 —— 禁硬删）→ 无风险
- 环校验深度 10 是防御性上限，正常业务链长 ≤3 → 触发即提示「替代链异常」

## Migration Plan

`019-substitute-material.sql`：单条 `ADD INDEX IDX_MDM_ITEM_ALT (ALT_ITEM_CODE)`，marker 单次执行，回滚 DROP INDEX。零数据变更。

## Open Questions

- 无
