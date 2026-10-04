# Design

## Context

见 proposal.md（Why）。现状关键点：

- 1.5.1 已交付：`erp_mdm_exchange_rate`（序列复合索引）+ `MdmExchangeRateService.create`（校验组：必填/ISO/精度 + `requireNoGapOrOverlap` 区间算法）+ 版本快照 + `MDM.RATE.CREATED` 事件（幂等键含生效日）
- **项目零 Excel 库**（后端无 poi/easyexcel、前端无 xlsx）—— 决策：CSV/粘贴零依赖
- 先例：物料批量停用（`disable-batch` 逐条分组结果）、客户建档 `forceCreate`（查重放行）——「按行独立+部分失败」语义已有
- 菜单 `M1-5-2 批量更新 /m/1.5.2` 已在 099 种子（无需菜单迁移）
- `MdmExchangeRateDao.selectSequence` 已提供序列查询（预检衔接判定的输入）

## Goals / Non-Goals

**Goals:**
- 预检与提交共用同一套校验（零复制），前端预览与后端权威结果一致
- 部分失败语义可靠（行级独立事务），报告完整可留存
- 解析器健壮（BOM/分隔符/表头/空行/列数错位）

**Non-Goals:**
- 真 .xlsx 解析（载体偏差已记）、税率批量（1.6 域）、异步任务队列、超 500 行大批量

## Decisions

### D1 行结构与解析（前端，零依赖）

```
RawRow = { rowNo, cells[] }          // rowNo = 源文件/粘贴中的物理行号（含表头计数，便于对照）
模板列序（固定 7 列）：基础币种,报价币种,汇率类型,生效日期,失效日期,汇率值,来源文件编号

解析规则（FileReader / textarea 共用 parseRows(text)）：
  ① 统一换行 \r\n|\r|\n；BOM (\uFEFF) 剥离
  ② 分隔符探测：首行含 \t → Tab；否则逗号（简单 CSV，字段不含逗号——模板说明约定）
  ③ 跳过：空行、以 # 开头的说明行、表头行（首列=「基础币种」或 baseCcy）
  ④ 列数 < 7 → 该行标「列数不足」；> 7 截断并标警告
  ⑤ 七列映射为 MdmExchangeRate 候选对象（rate 用 Number、日期字符串直传）
```

- **不做通用 CSV 引号转义**：模板字段（币种/日期/编号/数字）天然无逗号——design 明确裁剪，偏差记入 risk

### D2 服务层：`MdmExchangeRateBatchService` 两方法

```
preview(List<RateRow> rows, String defaultSourceFileNo):
  归一化：行内 sourceFileNo 空 → 回填 defaultSourceFileNo
  批内分组：(base,quote,type) → 按 effectiveDate 排序
  对每行：
    a. 字段校验（复用 1.5.1 requireFields 语义 —— 抽为可复用方法：
       requireFields(rate) 改为接收已归一化对象并返回/抛 422 → 批量改为
       **捕获型**：BatchValidate.validate(rate) 返回 reason 或 null，
       单条路径继续抛异常语义（两层适配：validate 返回 null/原因，
       单条包装一层 if(reason) throw）
    b. 区间衔接（复用 selectSequence）：批内前序段先物化为“已占用区间列表”
       → 对每行与「存量 ∪ 批内前序 valid 行」判定（扩展现有
       requireNoGapOrOverlap 的 seq 来源：**重构为传入 List 参数** 而非 DAO 直查）
  返回 List<{rowNo, valid, reason, normalized}>

batch(rows, defaultSourceFileNo):
  行数 > 500 → 422 整批拒绝
  按批内顺序逐行（不再预排序提交顺序——每行独立，但**同序列批内衔接依赖顺序**：
   提交序 = preview 排序后的序（前端按预检顺序提交，后端 batch 内部同样先分组排序
   再执行，保证与预检一致））
  每行 try { create(normalized) } catch (ServiceException e) → failed.add
  （create 自带事务……**行级独立事务**：batch 方法本身 **不加 @Transactional**，
   create 的 @Transactional REQUIRES 默认传播 → 每行独立提交；外层无事务
   ⇒ 单行失败不影响已成功行 ✓）
  返回 {total, succeeded, failed, details[]}
```

- **关键重构**：`requireNoGapOrOverlap` 与 `requireFields` 从「DAO 直查+抛异常」改为「接受区间列表+返回原因」的纯函数核心，单条路径包一层抛异常 —— 校验逻辑单点（D2 核心，避免批量复制算法）

### D3 事件与幂等

- 成功行 = 直接 `create()` → 快照与 `MDM.RATE.CREATED`（键含生效日）**零新增代码**
- 批内同一行重复提交（同键）→ DuplicateKey 409 → 该行 failed（幂等语义自然传导）

### D4 API

```
POST /api/mdm/exchange-rate-batch/preview  { rows:[...], defaultSourceFileNo }   ADMIN
POST /api/mdm/exchange-rate-batch/batch    { rows:[...], defaultSourceFileNo }   ADMIN
  rows = [{ rowNo, baseCcy, quoteCcy, rateType, effectiveDate, expireDate, rate, sourceFileNo }]
响应 preview: { results:[{rowNo, valid, reason, normalizedSummary}] }
响应 batch:  { total, succeeded, failed, details:[{rowNo, baseQuote, result, reason}] }
GET  不需要（模板由前端 Blob 生成）
```

### D5 页面 `/m/1.5.2` 单页四区

```
① 模板区：下载模板按钮（前端 Blob 生成 CSV+BOM）+ 列说明
② 录入区：文件上传（accept=.csv）+ 粘贴 textarea + 整批公共来源编号输入
   → 「解析并预检」按钮
③ 预检表格：行号|币对|类型|区间|汇率|结果(绿✓/红✗)|原因 —— 全绿才亮「确认提交」，
   存在红行时提交按钮仍可用但文案「提交（N 行将失败）」（后端为权威，spec 允许）
④ 结果报告：统计卡（总/成/败）+ 失败明细标红 + 「下载报告 CSV」+ 「查看汇率列表」
```

- 解析在前端完成后一次 preview；用户可改公共来源编号后重新 preview；batch 提交同一 rows

### D6 批次边界与错误口径

- 500 行上限（前后端双重校验：前端提示、后端 422）
- preview 与 batch 的「批内排序」必须一致（D2：batch 内部重排），否则衔接判定漂移
- 行级 `rowNo` 全链透传（解析 → 预检 → 报告），报告可对照源文件行

## Risks / Trade-offs

- [无引号 CSV 支持] → 模板字段无逗号约定 + design 明确裁剪；粘贴路径天然无此问题
- [requireFields/区间算法重构影响1.5.1 单条路径] → 重构为纯函数+薄包装，单条冒烟回归（原 30 场景中区间/必填组抽样复验）
- [batch 无外层事务的中途失败（进程崩溃）] → 部分行落库：与「按行提交」语义一致，重试时已成功行幂等键 409 标记重复，可接受
- [同序列批内顺序依赖提交序] → D2 规定 preview/batch 同一排序，前端提交预检结果顺序
- [前端解析正确性] → 冒烟覆盖 BOM/Tab/表头/列数错位/公共回填五情形

## Migration Plan

无迁移（零 schema 变更）。重启后端仅新 Controller/Service Bean；回滚 = 回退代码。

## Open Questions

（无 —— 载体、算法复用、排序一致性、上限、报告结构均已决策。）
