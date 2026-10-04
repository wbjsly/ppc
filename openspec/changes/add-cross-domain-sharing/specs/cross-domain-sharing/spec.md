# Spec Delta: cross-domain-sharing

## Purpose
客户主数据跨域共享出口：客户变更事件按 7.2 统一信封写入通用 Outbox 台账（幂等键去重、事件目录登记、事件流可查且桩口径明示），以及价格协议实体（头+行三类、集团/法人两级挂靠、状态机懒过期、按优先级的试算接口）。

## ADDED Requirements

### Requirement: 客户事件 Outbox 写入
客户域全部写操作（建档、变更、额度调整、停用、启用、冻结、解冻、合并、复审通过、临时额度回滚）成功后，系统 MUST 在同一事务内向 `erp_ops_outbox` 写入一条事件，信封 MUST 含：`EVENT_ID`、`EVENT_TYPE`（`MDM.CUSTOMER.*`）、`VERSION`、`RECORD_VERSION`（业务记录版本）、`OCCURRED_AT`、`SOURCE`、`IDEMPOTENCY_KEY`（`业务编码:记录版本` 格式）、`PAYLOAD`（变更后关键字段 JSON）、`STATUS=PENDING`。`IDEMPOTENCY_KEY` MUST 唯一，重复写入（同业务编码同版本重放）MUST 被唯一索引拒绝或安全跳过（C-0-06 幂等）。事件写入失败 MUST 阻断主操作回滚（同一事务语义）。

#### Scenario: 建档产生事件
- **WHEN** 客户集团视图创建成功
- **THEN** outbox 新增 `MDM.CUSTOMER.CREATED` 事件，IDEMPOTENCY_KEY=`客户编码:vN`，STATUS=PENDING，payload 含编码/名称/税号

#### Scenario: 幂等重放安全
- **WHEN** 同一业务编码与记录版本的事件被重复写入
- **THEN** 系统以幂等键拒绝或跳过，不产生重复事件行

#### Scenario: 事件失败回滚主操作
- **WHEN** outbox 插入因异常失败
- **THEN** 客户主操作回滚，不出现「主数据已变更但无事件」的不一致

### Requirement: 事件流查询与桩口径明示
系统 MUST 提供事件流查询页面与接口：支持按 `EVENT_TYPE`、`STATUS`、关键字（业务编码/幂等键）筛选与分页，可查看单事件 payload 详情与事件目录说明（生产方、消费方、幂等键规则、Schema 版本）。因消息总线未接入，消费方投递 MUST 明示「消息总线未接入，PENDING 为预期态」，MUST NOT 展示虚假的投递成功状态。

#### Scenario: 事件流筛选
- **WHEN** 按 `MDM.CUSTOMER.MERGED` 筛选事件流
- **THEN** 仅显示合并事件，含幂等键与发生时间，可展开 payload

#### Scenario: 投递状态桩明示
- **WHEN** 查看任一 PENDING 事件的投递信息
- **THEN** 页面标注「消息总线未接入，PENDING 为预期态」，无 DELIVERED 伪状态

### Requirement: 价格协议建档与两级挂靠
系统 MUST 支持创建价格协议头：`PA_CODE` 由 `PA-` + 4 位流水自动生成且创建后不可改；类型 MUST 为 `EXCLUSIVE`（客户专属价）、`LADDER`（量价阶梯）、`TIME`（时间价）三者之一；挂靠 MUST 二选一——客户集团级或法人视图级（FR-4.1-6-2 两级共享），两者同时为空或同时有值均阻断；必填名称、生效日期，失效日期为空表示不限期。协议 MUST 至少含一行价格行：SKU 须为启用物料；`LADDER` 类型行 MUST 携带 `MIN_QTY`/`MAX_QTY` 且同协议同 SKU 的数量区间 MUST NOT 重叠；非阶梯类型行 MUST 携带单价且数量区间留空。

#### Scenario: 编码自动生成且锁定
- **WHEN** 创建协议成功后提交修改协议编码
- **THEN** 首次创建返回 `PA-0001` 序列编码，变更提交被阻断提示编码不可改

#### Scenario: 挂靠二选一校验
- **WHEN** 协议同时传入集团与法人视图挂靠，或两者都未传
- **THEN** 系统阻断保存并提示必须且只能挂靠其一

#### Scenario: 阶梯区间重叠阻断
- **WHEN** LADDER 协议某 SKU 新增行区间 [10,20) 与既有 [5,15) 重叠
- **THEN** 系统阻断保存并指出重叠区间

### Requirement: 价格协议状态机与懒过期
协议状态 MUST 为 未生效/生效中/过期/停用：创建时按生效日期与当天关系判定未生效或生效中；查询时系统 MUST 对 `EXPIRE_DATE < 今天` 且状态为生效中/未生效的协议执行懒过期回写（幂等）；停用为手工操作且原因必填；停用与过期协议 MUST NOT 参与试算。已过期与停用状态 MUST 拒绝编辑价格行（仅可查看），未生效与生效中可编辑（原因必填入版本快照）。

#### Scenario: 懒过期回写
- **WHEN** 协议失效日期已过且用户打开协议列表
- **THEN** 该协议状态被回写为过期并留快照，试算不再命中

#### Scenario: 停用协议不参与试算
- **WHEN** 生效中的协议被手工停用后执行试算
- **THEN** 试算结果不包含该协议

### Requirement: 价格试算接口
系统 MUST 提供试算接口：入参 客户挂靠（集团或法人视图）+ SKU + 数量 + 日期，返回当日生效的适用协议。匹配规则：①协议须状态生效中且日期区间覆盖所查日期；②优先级 **EXCLUSIVE > LADDER > TIME**，高优先级命中即返回；③同优先级多协议命中时取生效日期最新者；④LADDER 类型 MUST 进一步按 `MIN_QTY ≤ 数量 ≤ MAX_QTY` 匹配价格行，无区间命中视为该协议不适用继续向低优先级查找。全部不命中时 MUST 返回「无适用协议」明示而非空对象（销售域的价格阻断留待后续）。挂靠匹配 MUST 同时考虑客户集团级与该客户法人视图级协议（法人级优先于集团级同类型）。

#### Scenario: 优先级命中
- **WHEN** 同客户同 SKU 同时存在生效中的 EXCLUSIVE 与 LADDER 协议
- **THEN** 试算返回 EXCLUSIVE 协议及其单价

#### Scenario: 阶梯数量区间
- **WHEN** LADDER 协议行区间为 [1,99]/[100,999]，试算数量 150
- **THEN** 返回 [100,999] 行的单价

#### Scenario: 无法人协议时用集团级
- **WHEN** 该法人视图下无协议但客户集团级有生效协议
- **THEN** 试算回退命中集团级协议并标注挂靠级别

#### Scenario: 无适用协议
- **WHEN** 所有协议均不满足生效条件或区间不命中
- **THEN** 返回明确的「无适用协议」与已排查原因（无生效协议/区间不命中/已停用）

### Requirement: 权限与审计
价格协议的增改与停用、事件流的管理类操作 MUST 要求 ROLE_ADMIN（403）；事件流查询、试算接口、协议列表查询须已认证（401）。协议全部写操作 MUST 生成版本快照（含变更前后值与原因），outbox 事件 MUST 记录操作后的 `RECORD_VERSION`。

#### Scenario: 非管理员建档协议
- **WHEN** ROLE_USER 提交创建价格协议
- **THEN** 返回 403，无数据写入
