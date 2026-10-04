# Spec Delta: rfq-inquiry

## Purpose
询价单（2.2.1 前半）：对已流转「待询价」的 PR 生成 RFQ、合格供应商 ≥ `MIN_QUOTE_COUNT` 卡控与紧急放行例外、报价录入与截止日锁价、延期/追加供应商处置、事件与权限，落实 FR-4.2-2-1、C-4.2-01、BR-4.2-13。

## ADDED Requirements

### Requirement: RFQ 创建与供应商卡控
系统 MUST 支持对状态为 `PENDING_RFQ` 的 PR 生成询价单（整单），单号 `RFQ-YYYYMMDD-NNN` 按日流水且唯一；RFQ MUST 保存 PR 行快照（物料编码、需求数量、交付日期要求——PR 后续变更不影响已发 RFQ）并含 报价截止日期（默认 = PR 最早需求日 − 5 个工作日，可手改）、技术标准/图纸附件（桩）、发送模式（OFFLINE/ONLINE）。供应商选择 MUST 仅限 `QUALIFIED` 状态（多选）。卡控：合格供应商数 < `MIN_QUOTE_COUNT`（配置默认 3）→ 创建 MUST L1 阻断（C-4.2-01）并提示扩充供应商库或发起新供应商准入；**例外**：关联 PR 存在有效紧急放行（clearance 返回 emergency=true 且非例外状态）→ MUST 允许最低 1 家创建且 RFQ 标记 `EMERGENCY`。PR 状态非 PENDING_RFQ → 422；同一 PR 存在未关闭 RFQ → 422 防重。创建成功写 `PROC.RFQ.CREATED` 事件（幂等键 `RFQ单号:HEAD:vN`）。

#### Scenario: 三卡供应商阻断
- **WHEN** 选择 2 家合格供应商且关联 PR 无紧急放行
- **THEN** 创建 422，提示低于 MIN_QUOTE_COUNT（3）

#### Scenario: 紧急放行允许 1 家
- **WHEN** 关联 PR 有有效紧急放行，仅选 1 家合格供应商
- **THEN** 创建成功且 RFQ 标记 EMERGENCY

#### Scenario: 非待询价 PR 拒绝
- **WHEN** 对「待确认」的 PR 生成 RFQ
- **THEN** 422 提示仅限已流转待询价的 PR

#### Scenario: 同 PR 防重
- **WHEN** 同一 PR 已有未关闭 RFQ
- **THEN** 422 提示已有进行中的询价单号

### Requirement: 发送、报价录入与截止锁价
RFQ 状态机 MUST 单点守卫：`DRAFT → SENT → QUOTING → QUOTED_CLOSED → AWARDED`（旁路 CLOSED，人工关闭原因必填）。`send`（线下确认发出）→ SENT（ONLINE 模式仅记发送状态，实际邮件/门户为桩）；**报价录入 MUST 仅 SENT/QUOTING 状态允许**（DRAFT 禁录 422、QUOTED_CLOSED 后禁改 422），每家供应商一报价（RFQ×供应商唯一，截止前重复录入 = 覆盖更新），字段：单价（>0，4 位小数）、交货期（天，>0）、MOQ（>0）、付款条件（NET30/NET60/NET90/款到发货）、报价有效期（日期）；首条报价录入 SENT → QUOTING。**截止日懒 sweep**：查询时超过报价截止日（跳周末工作日口径）→ 自动迁移 `QUOTED_CLOSED`（锁价）+ `PROC.RFQ.LOCKED` 事件，锁价后报价禁改、截止日禁延。

#### Scenario: 草稿禁录报价
- **WHEN** DRAFT 状态录入报价
- **THEN** 422 提示须先发出询价

#### Scenario: 锁价后禁改
- **WHEN** 已过截止日（sweep 锁价）后修改某报价单价
- **THEN** 422 提示已锁价不可修改

#### Scenario: 截止自动锁价
- **WHEN** 截止日已过，打开询价单列表
- **THEN** 该 RFQ 状态自动变 QUOTED_CLOSED 并产生 LOCKED 事件

### Requirement: 延期截止日与追加供应商（BR-4.2-13 处置入口）
**未锁价**（DRAFT/SENT/QUOTING）MUST 支持：① 延期报价截止日（新截止日必须晚于当前值，必填延期原因，留痕）；② 追加供应商（追加后总数仍须 ≥1，追加后须重新发出通知——线下确认桩）。**已锁价且报价数（有效） < MIN 且无紧急放行** → RFQ 置 `INSUFFICIENT_FLAG`（BR-4.2-13）：MUST 阻断定标并展示不足提示；此时延期/追加入口禁用，处置路径 = 作废本单（CLOSED，原因必填）后从 PR 重新发起（重询闭环）。

#### Scenario: 延期校验
- **WHEN** 输入早于当前截止日的新截止日
- **THEN** 422 提示新截止日晚于当前值

#### Scenario: 锁价不足阻断定标
- **WHEN** 锁价时有效报价 2 家 < 3 且无紧急放行
- **THEN** RFQ 标 INSUFFICIENT，定标按钮阻断并提示延期不可用、建议作废重询

### Requirement: 权限与页面
RFQ 创建/发送/报价/延期/追加/关闭 MUST 要求 ROLE_ADMIN（403）；查询须已认证（401）；页面挂载菜单 2.2.1（`/m/2.2.1`，M2-2-1 已注册）；候选 PR 下拉仅 PENDING_RFQ，供应商下拉仅 QUALIFIED，创建页 MUST 展示紧急放行状态（有/无提示）。

#### Scenario: 普通用户不可操作
- **WHEN** ROLE_USER 调用创建或报价录入接口
- **THEN** 返回 403，无数据写入
