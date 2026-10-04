# Proposal

## Why

菜单 1.5.2「批量更新」指向占位页。规格 FR-4.1-3-3（行 532）明确「通过 Excel 模板批量导入汇率/税率更新，逐行校验后批量发布」，S-4.1-04（行 717）描绘了财务每月初中间价批量更新的核心场景（行 552），BR-4.1-19（行 753）与异常表行 588 定义了「逐行校验、按行提交、失败标红、导入结果报告」的合规要求。1.5.1 汇率维护已交付单条校验组与区间算法，但**逐条录入无法支撑每月多币对的批量场景**。本轮补齐批量导入能力（载体按零依赖 CSV/粘贴实现，Excel 记偏差）。

## What Changes

### 功能一：批量导入（载体：CSV / 粘贴，零新依赖）

- **模板 CSV 下载**：表头 `基础币种,报价币种,汇率类型,生效日期,失效日期,汇率值,来源文件编号` + 说明行（类型枚举/ISO 格式/区间衔接规则）；纯前端生成 Blob 下载（BOM 头保证中文 Excel 兼容），不建后端模板接口
- **两种录入**：① 上传 `.csv` 文件（前端 FileReader 解析，UTF-8/BOM 容错、逗号/制表符自适应、跳过表头与空行）② 粘贴表格文本（Excel 单元格直接复制的 Tab 分隔文本）→ 均解析为行数组；「来源文件编号」支持整批公共值（行 552 一次公告覆盖多行，行内值缺省时回填）
- **预检（dry-run，不落库）**：`POST /preview` —— 逐行复用 1.5.1 校验组（必填四件 BR-4.1-16 / ISO 币种 / 6 位精度 / 失效≥生效）+ **区间衔接**（BR-4.1-17 传导）：批内同（币对×类型）按生效日排序，依次与存量链头尾及批内前序段衔接，违规行标注「相交/断档+可操作口径」；返回逐行 `{rowNo, valid, reason, normalized}` 供前端红绿标预览
- **确认提交**：`POST /batch` —— **按行独立执行**（BR-4.1-19）：每行独立事务调用既有 `MdmExchangeRateService.create`（快照 + `MDM.RATE.CREATED` 事件全复用），单行失败收集原因不回滚成功行；批次上限 500 行（防误传超大文件）
- **《导入结果报告》**：返回 `total/succeeded/failed` + 逐行明细 `{rowNo, result, reason}`，失败行标红；支持报告 CSV 下载（前端生成）

### 功能二：页面 `/m/1.5.2`

- `views/mdm/exchange-rate-batch/index.vue`：模板下载 → 上传/粘贴区 → **预检表格**（行号/币对/区间/汇率/校验结果红绿标+原因、公共来源编号输入）→ 确认提交 → **结果报告**（统计卡 + 失败明细标红 + CSV 下载）→ 成功数 >0 时提供「查看汇率列表」跳转 1.5.1
- `router/index.js` 注册 `/m/1.5.2`；`SecurityConfig` 写接口 ADMIN
- 无 outbox 新事件类型（行级复用 `MDM.RATE.CREATED`）

### 与规格的关系

| 规格条目 | 处理 |
|---|---|
| **FR-4.1-3-3 批量导入逐行校验发布（行 532）** | **补齐**（载体改 CSV/粘贴，格式偏差记） |
| **BR-4.1-16 导入缺必填该行标红不参与发布（行 750）** | **补齐**（预检+提交双层行级拦截） |
| **BR-4.1-19 逐行校验/按行提交/结果报告（行 753）** | **补齐**（行级独立事务 + 报告 + CSV 下载） |
| 行 588 部分失败语义 | **补齐**（成功行发布、失败行收集，不回滚） |
| 行 552 每月中间价场景 + 公共来源编号 | **补齐**（整批公共字段回填） |
| **BR-4.1-17 区间衔接传导** | **补齐**（批内排序+存量链衔接，复用 1.5.1 算法） |
| 真 .xlsx 模板解析 | **Non-Goal**（载体偏差：项目零 Excel 库，CSV/粘贴等价实现，偏差表记） |
| 税率/税码批量导入（FR-4.1-3-3 的「税率」半句） | **Non-Goal**（属 1.6 税码管理域，偏差表记） |
| 异步导入任务队列/超大批量 | **Non-Goal**（≤500 行同步执行） |

**已沿基线**：单条校验/快照/事件全复用 1.5.1 实现（`MdmExchangeRateService.create` 直调）、无审批、写接口 ROLE_ADMIN。

## Capabilities

### New Capabilities
- `exchange-rate-batch-import`: 汇率批量更新 —— CSV/粘贴零依赖载体与模板、行级预检（含区间衔接传导）、按行独立提交与部分失败语义、导入结果报告、页面全流程

### Modified Capabilities
<!-- 无：openspec/specs/ 为空（前十七变更未归档）；exchange-rate-maintenance 为兄弟能力，其单条校验被本能力复用但需求不变 -->

## Impact

- **后端**：无新表无迁移；新 `MdmExchangeRateBatchService/Impl`（preview/batch 两方法，复用 `MdmExchangeRateDao.selectSequence` 与 `MdmExchangeRateService.create`）+ `MdmExchangeRateBatchController`（`/api/mdm/exchange-rate-batch/preview|batch`）；`SecurityConfig` 写接口 ADMIN
- **前端**：`api/mdm/exchange-rate-batch.js`、`views/mdm/exchange-rate-batch/index.vue`（模板/解析/预检/报告）、`router/index.js` `/m/1.5.2`
- **数据**：无 schema 变更；写入走既有汇率表
- **依赖**：1.5.1 的校验组与区间算法（零复制直调）、022 OutboxPublisher（行级事件）
