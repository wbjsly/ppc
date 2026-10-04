# Design

## Context

见 proposal.md（Why）。现状关键点：

- `MdmItemServiceImpl.disable(id)`（行 197-225）已具备：已停用校验、替代引用 409 + 前 10 清单（`findSourcesByTarget`/`countSourcesByTarget`）、`queryDownstreamReferences` 空桩、乐观锁、`DISABLE` 快照 —— 但**无停用原因**、无影响分析独立入口
- `MdmItem.STATUS` 注释为「1 Active / 0 Inactive」，全项目读路径多为 `status='1'` 正向筛选（`options()`、替代列表、分类停用校验）
- 菜单种子 `M1-2-4 物料停用 → /m/1.2.4` 已在 `099-menu-seed.sql`，router/views 无此页
- `erp_mdm_item_version.OP_TYPE` 现有 `CREATE/UPDATE/DISABLE`（分类侧另有 MERGE/MOVE）；快照由 `saveSnapshot(item, opType, diffSummary)` 生成，`snapshotJson()` 为新值
- 项目基线：无审批、立即生效、乐观锁、禁硬删、写接口 ADMIN、`R` 包装 + 业务码在 body

## Goals / Non-Goals

**Goals:**
- 停用/启用/归档三态操作收敛到专属工作台，可追溯（原因入快照 diff）
- 影响分析先行：真实替代引用硬阻断 + 下游桩口径明示，二者在接口层就分开表达
- 批量停用逐条语义与分类合并的批量改挂保持一致（部分失败不回滚成功项）

**Non-Goals:**
- 归档库/数据迁移（降级为状态标记，见 proposal 偏差表）
- 解除归档（归档为终态；本期无回退路径）
- PO/SO/BOM 真实引用接入（等采购/销售/生产模块）
- 通知单的多渠道推送（事件桩仅日志留痕）

## Decisions

### D1 状态语义：`1 启用 / 0 停用 / 2 已归档`（单列扩展，非新列）
- **选择理由**：`STATUS` 是 varchar，写入 `2` 无需迁移；归档是物料生命周期状态而非附加属性，单独 `ARCHIVE_FLAG` 会造成 `status='0' && flag='2'` 的双列组合态与到处散落的判断
- **风险面**：全项目 `status='1'` 正向筛选不受影响；唯一需要检查的是**反向**筛选 `status='0'` 的位置（如有"仅停用"口径需确认是否应含已归档）—— 实施时 grep `getStatus, "0"` / `eq.*status.*0` 逐一核对，业务语义上「不可引用」集合应为 `status != '1'`
- **替代方案**：新列 `ARCHIVE_STATUS` —— 拒绝，双状态机组合复杂且无查询收益

### D2 影响分析独立端点：`GET /api/mdm/items/{id}/impact`
- 返回结构：`{ substituteSources: [...], substituteTotal: n, downstream: [], downstreamStub: true, downstreamNote: "采购/销售/生产模块未接入，暂无数据" }`
- **理由**：停用确认框需要预览数据，不能靠「点了停用才知道 409」；`downstreamStub` 布尔让前端区分「真的无引用」与「桩未接入」，满足 spec「不得伪装为确定性结论」
- 停用执行时**重新跑一遍**引用校验（不信任前端预览），复用现有 409 逻辑

### D3 停用/启用/归档共用一个状态机守卫
```
disable:  require status=='1'          → '0'
enable:   require status=='0'          → '1'   + 替代指向目标须有效(=='1')
archive:  require status=='0' && 无替代引用方 → '2'
```
- 每步操作：原因必填（参数校验 422）→ 守卫 → 乐观锁 `updateById(patch, verNo)` → `saveSnapshot(opType, "status: X → Y；原因：<reason>")`
- 启用时的替代校验：查自身 `altItemCode` 非空 → 取目标 `status`，非 `1` → 422「替代目标已失效」（复用 `requireSubstituteValid` 的目标存在/状态检查分支，不重走环回溯 —— 停用前配置时已保证无环）

### D4 批量停用：`POST /api/mdm/items/disable-batch`，请求体 `{ ids: [], reason: "..." }`
- 逐条调用与单条相同的 service 私有方法；`try/catch` 收集 `{ id, success, message }`
- **不加 `@Transactional` 全局回滚**：与分类合并的逐条物料快照同语义，成功项即生效；返回 `{ succeeded: [...], failed: [{id,code,message}] }`
- 单一 reason 应用于整批（一次操作一个意图），快照内逐条记录

### D5 停用原因入快照：扩展现有 `disable` 而非新方法
- 现有 `disable(id)` 签名改为 `disable(id, reason)`，diff summary 由 `"status: 1 → 0"` 变为含原因；`item/index.vue` 行内停用弹窗同步加必填原因输入（原弹窗只有确认+提示文案）
- **理由**：停用只有一条执行路径，双路径会导致校验漂移；工作台与列表页共用同一端点

### D6 通知单桩：`MDM.ITEM.DISABLED` 日志
- 与既有 `MDM.ITEM.PUBLISHED/UPDATED` TODO 桩同款：`log.info` 一行 + 注释标注推送目标；不建表、不发消息

### D7 前端：`views/mdm/item-disable/index.vue` 独立页
- 列表：关键字 + 状态筛选（启用/停用/已归档/全部）+ 分页，复用 `GET /items` 分页接口（加 `status` 参数需评估现有接口是否已支持 —— 若无则工作台本地传 `status` 查询参数给现有分页接口，或复用列表接口后前端过滤；实施时以现有 `MdmItemController` 分页参数为准）
- 行操作：停用 / 启用 / 归档（按状态显示），「影响分析」按钮打开抽屉展示两类引用 + 桩说明
- 批量：勾选多行（仅启用态可选）→ 统一停用弹窗（原因必填）
- 确认弹窗统一模式：原因必填 textarea + 影响分析摘要 + 阻断错误回显（拦截器单点弹错，catch 不重复提示 —— 项目铁律）

## Risks / Trade-offs

- [归档终态无回退，误归档只能改库] → 归档入口放确认框二次确认 + 展示「归档为终态」警示文案；解归档留待有真实需求再提案
- [status='2' 影响未知读路径] → 实施任务里加一步：全局 grep `status` 比较点，核对每个查询的业务语义（不可引用 = `!= '1'`）
- [批量部分失败导致用户对"成功"感知模糊] → 返回体按成功/失败分组，前端弹汇总（成功 N 条 / 失败 M 条+原因列表）
- [停用原因加入现有 disable 端点是破坏性签名变更] → 前端仅两处调用（列表页、新工作台），同一变更内一起改，无外部消费者
- [桩明示可能被误读为"功能没做完"] → 这正是意图：spec 行 516 要求展示引用列表，未接入时诚实标注口径优于静默空列表

## Migration Plan

无迁移脚本（无 DDL）。部署即生效：重启后端（新端点 + 状态守卫）、前端 HMR（新路由页）。回滚 = 回退代码；已写入的 `2` 状态数据在回滚后仍可被 `status != '1'` 逻辑正确处理（原逻辑本就只认 `1`）。

## Open Questions

- 工作台分页是否需要后端新增 `status` 查询参数（视 `MdmItemController` 现有分页参数而定）—— 实施时看代码决定，不改变 spec 行为。
