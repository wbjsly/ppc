# Proposal

## Why

菜单 1.6「税码管理」下三件套（1.6.1 税码维护 / 1.6.2 政策更新 / 1.6.3 批量导入）均为占位页，后端零税码代码。规格流程三与汇率共用同一套规则（FR-4.1-3-2 税率更新、BR-4.1-16/17/18、C-4.1-04、S-4.1-07），且下游 4.14 税务域已明确依赖税码主数据（C-4.14-01 税率缺失/过期阻断计税、BR-4.14-10 按业务发生日取税率）——税码主数据不落地，计税与开票链路无从取数。本变更一次性补齐三个菜单。

## What Changes

### 功能一：1.6.1 税码维护

- **字段模型**：税码编号、税率值（精度 4 位，百分数存储如 13.0000）、生效/失效日期（闭区间）、适用范围（DOMESTIC 国内 / EXPORT 出口 / EXEMPT 免税）、政策文号 + 计税方式（GENERAL 一般 / SIMPLIFIED 简易 / DIFFERENTIAL 差额）、税率类型（STANDARD 标准 / LOW 低税率 / ZERO 零税率 / EXEMPT 免税）
- **规则完全复用汇率域**：序列键 = 税码编号（BR-4.1-17 字面）；闭区间不重叠不断档（复用 `ExchangeRateRules.checkInterval`）；已失效历史不可篡改（BR-4.1-18）；税码编号创建后不可改（C-4.1-01）；政策文号/生效失效日期必填（BR-4.1-16 / C-4.1-04）；变更原因 ≥2 字入快照；乐观锁 409
- **试算接口** `GET /trial`：按 税码+日期 取当时生效记录（预铺 4.14 计税，缺失明示非空 reasons，复用汇率 trial 口径）
- **版本快照 + 事件**：CREATE/UPDATE 快照；outbox `MDM.TAXCODE.CREATED/UPDATED`，幂等键 `税码编号:生效日期:{记录ID前8位}:vN`（吸取汇率域实测教训：不含记录身份时「软删后同税码同日期重建」会撞旧键 409 回滚）
- **列表**：分页 + 税码/适用范围/计税方式/计算态筛选 + 三计算态标签；页面 `/m/1.6.1`
- 无独立历史页（版本走既有 versions/diff 接口，决策已确认）

### 功能二：1.6.2 政策更新（政策台账）

- **新表 `erp_mdm_tax_policy`**：政策文号（唯一）、政策名称、发文机关、发布日期、生效日期、摘要、备注 + 审计/乐观锁
- 台账 CRUD（编码类基线：政策文号创建后不可改、禁硬删——被税码引用时 422 阻断删除）
- **关联变更回链**：税码记录带政策文号；政策详情/行内展示「该政策关联的税码变更记录」（按政策文号匹配税码表 + 快照 diff 流水）；政策维度查询接口 `GET /{id}/tax-codes`
- 页面 `/m/1.6.2`：台账列表 + 新建/编辑弹窗 + 关联变更抽屉

### 功能三：1.6.3 批量导入（复用 1.5.2 CSV 机制）

- 表头 `税码编号,税率值,生效日期,失效日期,适用范围,政策文号,计税方式,税率类型`（后两列可缺省取默认 GENERAL/STANDARD）+ 公共政策文号回填
- 模板下载 / 上传 CSV / 粘贴 Tab 文本（复用 `rate-import.js` 同款零依赖解析，抽公共或复制实现）
- 预检 dry-run：逐行复用 1.6.1 校验组 + 同税码区间衔接（存量链 ∪ 批内前序段）；确认提交：行级独立事务调用 `MdmTaxCodeService.create`（快照+事件全复用），>500 行 422
- 《导入结果报告》逐行成功/失败 + CSV 下载；页面 `/m/1.6.3`

### 基础设施

- **迁移 026**：`erp_mdm_tax_code` + `erp_mdm_tax_code_version` + `erp_mdm_tax_policy` 三表（幂等，序列复合索引 `(TAX_CODE, EFFECTIVE_DATE)`）
- 路由 `/m/1.6.1~1.6.3`（菜单 M1-6-1/2/3 已注册）；写接口 ROLE_ADMIN（**SecurityConfig 追加 tax-codes/tax-policies/tax-code-batch 的 POST/PUT/DELETE 路径规则**——实施揭示：项目无 ADMIN 注解机制，写权限一律在 SecurityConfig 显式路径配置）；下游引用校验桩（4.14 未落地，政策被引用校验本变更内做）

## Capabilities

### New Capabilities

- `tax-code-maintenance`: 税码维护：字段模型与枚举、按税码序列的区间硬阻断、历史不可篡改、按日期试算、版本快照与 MDM.TAXCODE 事件、权限口径
- `tax-policy-register`: 政策台账：政策登记与唯一性、编码不可改/被引用禁删、关联税码变更回链查询
- `tax-code-batch-import`: 税码批量导入：CSV/粘贴解析、预检与行级提交、公共政策文号回填、结果报告

### Modified Capabilities

（无——汇率域既有能力只被复用，不改其规格行为）

## Impact

- 后端：`MdmTaxCodeController/Service/Impl/DAO`、`MdmTaxPolicyController/Service/Impl/DAO`、`MdmTaxCodeBatchController`；复用 `ExchangeRateRules`（区间+字段校验抽泛化或参数化）、`OutboxPublisher`、`CreditLimitSupport` 式共享组件思路
- 前端：`views/mdm/{tax-code,tax-policy,tax-code-batch}/index.vue`、`api/mdm/tax-code.js`、`api/mdm/tax-policy.js`、router 三路由
- 迁移 `026-tax-code.sql`；SecurityConfig 追加税码三路径写规则（见上，实施揭示修正）

### 偏差表（规格 vs 当前落地）

| 规格条目 | 本变更口径 | 级别 |
|---|---|---|
| FR-4.1-3-3 税率批量导入 Excel | CSV/粘贴零依赖（沿汇率域 add-exchange-rate-batch 已入偏差的同口径） | 载体偏差 |
| FR-4.1-3-2「税率值」精度 | 规格未定精度，取 4 位小数（增值税百分率常规精度），入 design | 解释性口径 |
| 政策更新（1.6.2）语义 | 规格无直接定义，由 S-4.1-07 + 政策文号字段推导为「政策台账+关联回链」（需求已确认） | 推导口径 |
| 独立历史页 | 不建，版本走 versions/diff 接口（需求已确认） | 范围裁剪 |
| 下游引用桩（4.14 计税/开票引用税码） | 4.14 未落地，桩 TODO；政策被税码引用的删除校验本变更内实现 | 沿基线 |
