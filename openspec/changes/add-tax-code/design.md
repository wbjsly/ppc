# Design

## Context

- 规格流程三税码与汇率共用规则（BR-4.1-16/17/18、C-4.1-04、S-4.1-07）；下游 4.14 明确依赖（C-4.14-01、BR-4.14-10 按业务发生日取税率）。
- 既有可复用资产：`ExchangeRateRules`（`validateFields` 挂 `MdmExchangeRate` 类型、`checkInterval(List<MdmExchangeRate>, from, to)` 纯函数五段判定）、`OutboxPublisher`（幂等键 `bizCode:vN` + DuplicateKey→409）、`MdmExchangeRateBatchServiceImpl`（行级独立事务模式：方法不加 @Transactional）、`frontend/src/utils/rate-import.js` 的 `parseRows(text)`（通用文本→行数组）、1.5.1 lifecycle 计算态与 versions/diff 接口形态。
- 现状：税码域零代码；迁移下一号 026；菜单 M1-6-1/2/3 已注册、路由未挂。
- 已知缺陷教训（本变更内规避）：① 幂等键不含记录身份 → 软删后同键重建 409 永久卡死（汇率实测）；② MP wrapper 不可 set `exist=false` 字段；③ `eq(condition,...)` condition=false 仍求值；④ 批量方法不得加外层事务。

## Goals / Non-Goals

**Goals:**
- 三菜单端到端落地，区间/快照/事件语义与汇率域同构；区间算法与批量模式保持单点复用不复制
- 幂等键带记录身份，从第一天规避汇率域撞键缺陷

**Non-Goals:**
- 不修复汇率域已存在的幂等键缺陷（另立变更，待用户确认）
- 不实现 4.14 计税/开票侧逻辑（本变更只提供 trial 取数口径与引用桩 TODO）
- 不建独立税码历史页（versions/diff 接口覆盖，决策已确认）
- 不做政策文号外键强校验（见 D4）

## Decisions

### D1 数据模型（迁移 026，三表幂等）
- `erp_mdm_tax_code`：ID、TAX_CODE（≤32，大写字母数字短横线）、TAX_RATE DECIMAL(10,4)、EFFECTIVE_DATE/EXPIRE_DATE DATE、SCOPE（DOMESTIC/EXPORT/EXEMPT）、POLICY_NO VARCHAR(64)、CALC_TYPE（GENERAL/SIMPLIFIED/DIFFERENTIAL）、RATE_KIND（STANDARD/LOW/ZERO/EXEMPT）、CHANGE_REASON、审计四件、DEL_FLAG、VER_NO；索引 `IDX_MTC_SEQ (TAX_CODE, EFFECTIVE_DATE)`、`IDX_MTC_POLICY (POLICY_NO)`（政策回链查询用）
- `erp_mdm_tax_code_version`：沿 025 快照表形态（ENTITY_ID+VERSION_NO 唯一、SNAPSHOT_JSON、DIFF_SUMMARY、OP_TYPE、CHANGE_REASON）
- `erp_mdm_tax_policy`：ID、POLICY_NO（UNIQUE UK）、POLICY_NAME、ISSUER、ISSUE_DATE、EFFECTIVE_DATE、SUMMARY、REMARK、审计+DEL_FLAG+VER_NO
- 备选：政策表并入税码表字段——政策是独立实体需台账 CRUD，弃

### D2 区间算法抽共享核心，字段校验各域独立
- 新增 `com.erp.common.IntervalRules`：`record IntervalSeg(LocalDate effective, LocalDate expire)` + `check(List<IntervalSeg> seq, LocalDate from, LocalDate to)`（原五段判定逻辑原样迁移）；`ExchangeRateRules.checkInterval` 改为薄委托（行为不变，1.5.1/1.5.2 回归验证）；`TaxCodeRules` 包装它做 422/行级收集
- `TaxCodeRules.validateFields(MdmTaxCode)`：编号格式/枚举三选一/税率 >0 ≤100 四位小数/零税率矛盾/日期关系/政策文号必填——与汇率同「单条抛、批量收」双路径模式
- 备选：把 `checkInterval` 复制到 TaxCodeRules——两处实现必漂移（ExchangeRateRules 注释已申明「算法单点，防批量复制漂移」），弃

### D3 幂等键含记录身份
`bizKey = TAX_CODE:EFFECTIVE_DATE:{ID前8位}`，outbox 键 = `bizKey + ":v" + versionNo`。同记录同版本重放 → 409（C-0-06 语义保留）；软删后重建（新 ID）→ 新键 → 放行。备选：仅 `税码:生效日:vN`（沿汇率）——实测缺陷，弃；仅 `{id}:vN`——丧失文号可读检索性，弃。

### D4 政策关联为文号文本匹配，无外键
税码 POLICY_NO 自由文本（FR-4.1-3-2 字面即文号字段，政策可后补登记）；回链 = 按文号查税码表（D1 IDX_MTC_POLICY）+ 拼版本流水；政策删除校验 = `COUNT(tax_code WHERE POLICY_NO=? AND DEL_FLAG=0)`，>0 → 422 带引用计数。备选：FK 级联校验（税码创建时须政策已登记）——把登记顺序强加给业务（政策文号先于台账存在是常态），弃。

### D5 批量沿汇率模式，解析函数跨域复用
- 后端 `MdmTaxCodeBatchService`：`preview(rows, defaultPolicyNo)` / `batch(rows, defaultPolicyNo)`，batch **方法不加 @Transactional**（行级 create 独立提交）、>500 行 422、同税码批内按生效日排序与「存量链∪批内前序段」衔接判定、计税方式/税率类型缺省回填 GENERAL/STANDARD、公共政策文号回填
- 前端：`utils/tax-import.js` 自带 8 列解析器（实施揭示：`rate-import.js` 的 `parseRows` 为汇率 7 列专用含表头嗅探，并非通用函数——D5 初稿「直引 parseRows」不成立）。解析规则沿同套（BOM 剥离/换行归一/Tab 或逗号探测/跳表头注释空行/前 6 列必需短行标注），税码专用 `downloadTemplate`/`downloadReport`；**`rate-import.js` 源码零改动**，1.5.2 不回归风险
- 备选：把 rate-import.js 拆成公共 csv utils——需要改 1.5.2 引用面并回归，收益仅省一个已稳定函数，弃（parseRows 已导出可直引）

### D6 接口与页面
- `MdmTaxCodeController /api/mdm/tax-codes`：page/getOne/create/update/delete/trial/`{id}/versions`/`{id}/diff`（形态同 1.5.1）
- `MdmTaxPolicyController /api/mdm/tax-policies`：page/create/update/delete/`{id}/tax-codes`（关联回链）
- `MdmTaxCodeBatchController /api/mdm/tax-codes/batch`：preview/batch
- 前端 `views/mdm/{tax-code,tax-policy,tax-code-batch}/index.vue` + `router/index.js` 三路由；trial 弹层内嵌税码页；政策关联用抽屉（税码清单+版本流水倒序）
- 权限：写接口 ROLE_ADMIN，**SecurityConfig 追加三路径 POST/PUT/DELETE 规则**（实施揭示：项目写权限全部集中在 SecurityConfig 显式 antMatchers，无注解机制——提案初稿「ADMIN 注解沿用」为误判，已回填）

## Risks / Trade-offs

- [抽取 IntervalRules 动到汇率域共享代码] → 纯逻辑等价迁移 + 委托，1.5.1/1.5.2 回归列入验证任务
- [政策文号 LIKE/等值回链无 FK，税码侧文号可能指向未登记政策] → 设计有意为之（D4），关联抽屉对未登记政策显示「政策未登记」提示，不报错
- [026 中途失败无 ADD COLUMN IF NOT EXISTS 语义] → 仅 CREATE TABLE IF NOT EXISTS，无裸 ALTER；marker 幂等
- [同名 CSV 解析双处演进] → 实施揭示两域列结构本就不同（7 列 vs 8 列），各自独立实现反而清晰；以「rate-import.js 零改动」保证 1.5.2 不回归
- [快照 JSON LIKE 筛选（若税率页需要）量大后慢] → 税码页筛选全走实体列，不走快照 LIKE（与汇率 history 不同，本变更无全局时间线）

## Migration Plan

迁移 026 追加末尾编号（当前 025 之后）；部署即生效。回滚 = 还原三页面路由与后端代码，表留存无害（业务空表）。

## Open Questions

（无——三菜单范围、字段、幂等键、政策关联口径均已在需求沟通中确认）
