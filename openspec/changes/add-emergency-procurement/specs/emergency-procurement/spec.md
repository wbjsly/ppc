# Spec Delta: emergency-procurement

## Purpose
紧急采购（2.1.3）：关联已批准 PR 的紧急申请单 EA、采购总监特批放行与补齐时限、比价资料补齐登记、逾期例外与通道阻断联动、RFQ/PO 放行桩，落实 S-4.2-04、BR-4.2-11、L1321 异常场景与 `EMERGENCY_FILL_DAYS` 参数。

## ADDED Requirements

### Requirement: 紧急申请发起与前置校验
系统 MUST 仅允许对状态为 `APPROVED` 或 `PENDING_RFQ` 的 PR 发起紧急采购申请（S-4.2-04 语义）；发起 MUST 填写：紧急事由枚举（产线停线/客户紧急订单/其他）、事由说明（≥2 字）、期望到货日；单号 MUST 为 `EA-YYYYMMDD-NNN` 按日流水且唯一。发起前置校验：① 申请人紧急通道为 `BLOCKED` → MUST L1 硬阻断并返回阻断原因与恢复路径（L1321 创建权限阻断）；② 同一 PR 已存在未关闭 EA（状态非 REJECTED/CLOSED）→ MUST 422 防重；③ PR 状态不符 → MUST 422。创建成功 MUST 初始状态为「特批中」并写 `PROC.EMERGENCY.CREATED` 事件（幂等键 `EA单号:HEAD:vN`，含记录身份，重放 409）。

#### Scenario: 仅已批准 PR 可发起
- **WHEN** 对状态「待确认」的 PR 尝试发起紧急申请
- **THEN** 系统 422 拒绝并提示仅限已批准/待询价的 PR

#### Scenario: 通道阻断拒绝创建
- **WHEN** 申请人的通道状态为 BLOCKED 时发起
- **THEN** L1 硬阻断，返回阻断原因与「补齐+总监复核恢复」路径提示

#### Scenario: 同 PR 防重
- **WHEN** 同一 PR 已有特批中的 EA 时再次发起
- **THEN** 系统 422 提示存在未关闭的紧急申请单号

### Requirement: 采购总监特批与放行记录
特批为**采购总监单节点**（L2 降级，偏差表记）：**通过** MUST 填写特批原因（≥2 字）、记录特批人/时间，状态迁移「已特批放行」，生成放行记录：PR 单号、放行时间、**补齐截止日 = 特批日 + `EMERGENCY_FILL_DAYS`（配置项，默认 5）**，并写 `PROC.EMERGENCY.SPECIAL_APPROVED` 事件；**驳回** MUST 填写驳回原因（≥2 字）→ 状态「已驳回」+ `PROC.EMERGENCY.REJECTED` 事件。非「特批中」状态执行特批动作 MUST 422 防重；二次通过 MUST 拒绝（状态防重）。

#### Scenario: 特批通过生成截止日
- **WHEN** 特批通过于 2026-10-06，参数=5（跳周末）
- **THEN** 状态「已特批放行」，补齐截止日为 5 个工作日后的日期，事件入 outbox

#### Scenario: 驳回退回
- **WHEN** 特批驳回并填原因
- **THEN** 状态「已驳回」，原因可查，该 PR 可重新发起申请

#### Scenario: 重复特批拒绝
- **WHEN** 对已特批的 EA 再次提交通过
- **THEN** 系统 422 状态防重

### Requirement: 特批超时升级标记（懒 sweep）
系统 MUST 懒扫描「特批中」的 EA：停留超过 1 个工作日 MUST 打提醒标记、超过 3 个工作日 MUST 打升级标记（升级至采购总监上级语义，角色字段留痕；通知 TODO 桩），标记与扫描时间落库可查（沿 BR-4.2-10 模式，工作日跳周末近似）。

#### Scenario: 特批超时打标
- **WHEN** 某 EA 特批中停留 4 个工作日
- **THEN** 同时出现提醒与升级标记（两者独立置位）

### Requirement: 比价补齐登记
「已特批放行」的 EA MUST 可进入补齐（状态「补齐中」）并登记：实际报价家数（≥1 整数）、比价资料编号（必填）、补充说明、登记时间；登记完成 → 状态「已补齐」并写 `PROC.EMERGENCY.FILLED` 事件、关闭该申请的例外标记。附件上传为桩（MinIO 未落地）。已逾期/已补齐/已驳回状态 MUST NOT 再登记补齐（422）。

#### Scenario: 补齐完成
- **WHEN** 登记报价家数 1、资料编号 BJ-2026-001 并提交
- **THEN** 状态「已补齐」，放行记录保留截止日，事件入 outbox

#### Scenario: 报价家数下限
- **WHEN** 报价家数填 0
- **THEN** 系统 422 提示至少 1 家报价

### Requirement: 逾期例外与通道阻断联动（L1321）
系统 MUST 在查询时懒扫描：已特批放行/补齐中且**超过补齐截止日（跳周末工作日口径）**未补齐的 EA → 状态迁移「逾期例外」+ 例外标记 + 写 `PROC.EMERGENCY.OVERDUE` 事件 + **将申请人通道置 BLOCKED**（阻断原因含 EA 单号、阻断时间落通道台账）+ 合规例外清单推送 TODO 桩。逾期后补登记 MUST 被拒绝（须先经总监复核恢复，见 emergency-channel 能力）。

#### Scenario: 逾期联动阻断
- **WHEN** 某 EA 超过截止日 2 个工作日仍未补齐
- **THEN** 状态「逾期例外」、例外标记=1、申请人通道变 BLOCKED、事件入 outbox

#### Scenario: 逾期后禁止直接补齐
- **WHEN** 对「逾期例外」的 EA 提交补齐登记
- **THEN** 系统 422 提示须先经总监复核恢复通道后处理

### Requirement: RFQ/PO 放行桩
系统 MUST 提供 `GET /api/proc/emergency/clearance?prNo=` 查询：返回该 PR 的紧急放行信息（是否存在、EA 单号、特批人/时间、补齐截止日、补齐状态、紧急标识）；PR 无 EA → 返回明确「无紧急放行」结构（非 404 报错）。该接口供 2.2 转 PO 校验最低 1 家报价放行与 2.3 PO 紧急标记消费（BR-4.2-11），当前无调用方，返回结构 MUST 可独立验证。

#### Scenario: 有放行返回结构
- **WHEN** 查询已特批 EA 对应的 PR
- **THEN** 返回 EA 单号、截止日、紧急标识 true

#### Scenario: 无放行明示
- **WHEN** 查询从未发起紧急申请的 PR
- **THEN** 返回 `emergency: false` 的明确结构，不报错

### Requirement: 权限与页面接入
EA 发起、特批、补齐登记 MUST 要求 ROLE_ADMIN（403）；查询/放行桩须已认证（401）；页面挂载于菜单 2.1.3（`/m/2.1.3`，M2-1-3 已注册）双 Tab（申请 + 通道台账）；列表 MUST 展示 状态、特批信息、补齐截止日、逾期天数、例外/超时/升级标记。

#### Scenario: 普通用户不可操作
- **WHEN** ROLE_USER 调用发起或特批接口
- **THEN** 返回 403，无数据写入
