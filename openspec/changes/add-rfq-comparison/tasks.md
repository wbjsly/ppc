# Tasks

## 1. 基础设施（迁移 030 + 状态机）

- [x] 1.1 迁移 `030-rfq-comparison.sql`（rfq 头/行快照/供应商清单/报价 四表，RFQ_NO 唯一、(RFQ_ID,SUPPLIER_ID) 唯一、(RFQ_ID,LINE_NO) 唯一，IF NOT EXISTS 幂等）+ 实体/DAO ×4；验证：启动建表、重复启动幂等
- [x] 1.2 `RfqStateMachine` 迁移矩阵（D2 全边）+ `RfqSupport`（transition/事件键 RFQ单号:HEAD:vN/单号 MAX 取号）+ `RequisitionStateMachine` 补 `minusBusinessDays`（反向跳周末，默认截止日用）；验证：jshell 正反例（锁价后禁录/AWARDED 终态/作废重询路径）+ 反向工作日用例
- [x] 1.3 SecurityConfig `proc/**` 覆盖核对（curl 403 实测）+ 构建通过；验证：ROLE_USER 写 403、读 401

## 2. 询价后端（rfq-inquiry）

- [x] 2.1 创建 `create`：PR 必须 PENDING_RFQ → 同 PR 未关闭防重 → 供应商全 QUALIFIED → **clearance 放行判定（有效→minCount=1+EMERGENCY_FLAG；否则 3）→ 数量卡控两文案** → 截止日默认=最早需求日−5工作日（minusBusinessDays，可手改覆盖）→ 行快照+插三表+CREATED 事件；PR 候选（PENDING_RFQ+紧急放行徽标数据）、合格供应商候选接口；验证：curl 三卡控/防重/紧急 1 家/截止日跨周末计算
- [x] 2.2 发送与报价：`send`（OFFLINE 确认/ONLINE 状态桩 → SENT+事件）、`saveQuote`（状态守卫 DRAFT 422/锁价 422、字段校验四枚举、upsert、首录 SENT→QUOTING、双事件键 Q 前缀不撞）；验证：curl 草稿禁录/锁价禁改/覆盖更新/事件键
- [x] 2.3 截止锁价 sweep（`businessDaysBetween(deadline,now)>=1` → QUOTED_CLOSED+LOCKED+INSUFFICIENT 标记联动）+ 延期（未锁价/新日更大/原因≥2 留痕）+ 追加供应商（未锁价/全 QUALIFIED/并集≥1/重发桩）+ 作废重询（CLOSED 原因必填，PR 可重建 RFQ）；验证：SQL 回填截止日触发锁价、INSUFFICIENT、延期校验、作废后重建

## 3. 比价与定标后端（rfq-comparison-award）

- [x] 3.1 矩阵 `matrix`：有效报价均值与 ±20% 异常、加权总分（权重和=100 422、价格/交付相对分、剔除无效不计）、**含税单价按 RFQ 头 TAX_CODE_NO 调 MdmTaxCodeDao.selectHit（RFQ 创建日），未填/未命中「待税率」**、桩列占位；验证：curl 权重校验/异常标记/含税命中与待税率/剔除重算
- [x] 3.2 报价动作：`confirmAnomaly`（记确认人时间）、`excludeQuote`（原因必填、置无效）、`negotiate`（谈判后单价+说明，原始保留，双轨留痕 log+字段）；验证：curl 三动作与原始报价不被覆盖
- [x] 3.3 定标 `award`：前置链全断言（未锁价/不足/中选无效/异常未处理/分析表缺失/权重和≠100 → 各 422）→ AWARDED+定标字段快照+`AWARDED` 事件；`awarded?prNo=` 中选桩两态（有/无明示）；验证：curl 正常定标+六类阻断+桩两态+事件 diff

## 4. 前端页面

- [x] 4.1 `api/proc/rfq.js` + `views/proc/rfq-comparison/index.vue`：列表（状态/紧急/不足/截止/报价进度）+ 创建对话框（PR 候选含紧急徽标、QUALIFIED 多选、截止默认回显、税码编号可选）；验证：vite 页面可达
- [x] 4.2 详情抽屉：行快照、供应商与发出、报价录入表格（内联编辑）、**矩阵区块**（权重+含税+桩列+异常徽标/确认剔除+谈判列+总分排序）、延期/追加/作废重询、定标表单（中选+分析表+异常阻断提示）；写按钮 isAdmin 门控；验证：交互链路浏览器可达
- [x] 4.3 兄弟页回归：2.1.1~2.1.4 页面正常；验证：浏览器快速过页无 pageerror

## 5. 端到端验证

- [x] 5.1 接口冒烟全量：创建三卡控/紧急 1 家/防重、发送报价状态守卫、锁价 sweep+INSUFFICIENT、延期追加作废、矩阵权重/异常/含税/剔除、谈判双轨、定标六类阻断+成功、awarded 桩、401/403/ROLE_USER；全部断言通过
- [x] 5.2 浏览器全流程：造 PENDING_RFQ PR（复用 2.1.1 链路）→ 2.2.1 创建（<3 阻断演示→补足/紧急放行）→ 发出 → 逐家报价（3 家，含 ±20% 异常单）→ 矩阵（权重调整/异常剔除/谈判双轨/含税列）→ 锁价 → 定标（异常未处理阻断演示→处理后成功）→ awarded 桩核对；zhangsan 只读；截图留证、无 pageerror
- [x] 5.3 数据清理归种子态（rfq 四表 + outbox PROC.RFQ/PR 域全清 + 030 marker 留存）+ 兄弟回归（2.1.x/1.6.x 页面正常）+ `openspec validate add-rfq-comparison --strict` 通过 + 勾选全部任务
