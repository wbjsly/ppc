# Design

## Context

见 proposal.md（Why）。现状关键点：

- 汇率域零代码零表；菜单 `M1-5-1 汇率维护 /m/1.5.1` 已在 099 种子（无需菜单迁移），1.5.2/1.5.3 为兄弟菜单占位
- **可复用资产**：`OutboxPublisher`（022）、版本快照模式（016/020/023 三次实现）、`BaseEntity` 乐观锁、`SimilarityUtil`（本变更不用）、懒校验模式（021/023）
- 无币种字典（决策：ISO 4217 自由输入）；无状态机（决策：计算态展示）
- 财务域（FR-4.6-1-5 凭证折算、C-4.1-15 月结）为桩消费方

## Goals / Non-Goals

**Goals:**
- 区间校验正确且可解释（冲突/断档返回可操作的清单与口径）
- 历史 append-only 可靠（试算对历史日期的结果不因新记录漂移）
- 试算接口为财务域提供「命中 or 缺失明示」的诚实口径

**Non-Goals:**
- 批量导入（1.5.2）、历史汇率独立视图（1.5.3）、月结覆盖检查、汇率自动获取、税码

## Decisions

### D1 表设计（迁移 025，2 张表）

```sql
erp_mdm_exchange_rate
  ID PK, BASE_CCY CHAR(3), QUOTE_CCY CHAR(3),          -- ISO 4217 大写
  RATE_TYPE VARCHAR(8),                                 -- MIDDLE / BUY / SELL
  EFFECTIVE_DATE DATE NOT NULL, EXPIRE_DATE DATE NOT NULL,  -- 闭区间，均必填（消歧：无不限期占位）
  RATE DECIMAL(18,6) NOT NULL,                          -- 6 位精度 FR-4.6-4-3
  SOURCE_FILE_NO VARCHAR(64) NOT NULL,                  -- C-4.1-04 来源文件编号
  CHANGE_REASON VARCHAR(255),
  审计 + DEL_FLAG + VER_NO
  KEY IDX_MER_SEQ (BASE_CCY, QUOTE_CCY, RATE_TYPE, EFFECTIVE_DATE)
  KEY IDX_MER_TYPE (RATE_TYPE)

erp_mdm_exchange_rate_version   -- ENTITY_ID+VERSION_NO 唯一 + OP_TYPE + CHANGE_REASON
```

- **EXPIRE_DATE 必填**：消除「不限期记录永久占位导致序列无法续接」的歧义——每次更新都是显式区间段，新段接旧段（断档校验才有意义）
- **无状态列**：计算态（未生效/生效中/已失效）纯日期推导，避免「状态 vs 日期」双真源

### D2 区间冲突与断档算法（design 核心）

```
同序列 = (BASE, QUOTE, TYPE) 全等的记录集合，按 EFFECTIVE_DATE 排序
候选 = 序列中排除自身（编辑时）

判定顺序（新记录 N = [ne, nx]，既有 E = [ee, ex]，全部闭区间）：
1. 相交：ne <= ex && ee <= nx  → 422 冲突清单 [ee, ex]
   （闭区间共享端点 [1,6/30]+[7/1,12/31] 不算相交：ne(7/1) <= ex(6/30)? false → 放行 ✓）
2. 衔接定位：序列为空 → 放行（首条）
   N 接在链尾（ne == maxEx + 1 天）或 N 在链首之前（nx == minEe - 1 天，严格贴合）
   → 放行
3. 断档：
   右缺口：ne > 链中「上一条（ee < ne）最大 ex」+ 1 → 422「断档：新记录生效日须 ≤ {maxEx+1}」
   左缺口：nx < 链中「下一条（ee > nx）最小 ee」- 1 → 422「左侧缺口：新记录失效日须 ≥ {minEe-1}」
   （左缺口 = BR-4.1-17 右断档的镜像，spec「区间链断点」统一覆盖，design 记解释）
4. 插链中且与链首尾都贴合但内部空洞 → 实际被 1/3 捕获
```

- **实现**：单方法 `requireNoGapOrOverlap(base, quote, type, from, to, excludeId)`，SQL 取序列（≤500 条全量排序，区间序列规模天然小）→ 内存判定
- 变更记录时 `excludeId` 排除自身（自身旧区间不参与）

### D3 编辑语义（BR-4.1-18 append-only）

- 判定「已失效」= `EXPIRE_DATE < today` → 编辑与删除均 422（删除走 `@TableLogic` 软删但被业务守卫先拦）
- 生效中/未生效编辑：原因必填（≥2 字）→ 原子重跑 D2 校验 → 乐观锁 UPDATE + 快照 UPDATE + `MDM.RATE.UPDATED`
- BASE/QUOTE/TYPE **创建后锁定**（序列定位键，改键=脱离原序列破坏链条）→ 422（同编码锁定精神）

### D4 试算查询（D4 定为 `trial`）

```
trial(base, quote, date, type?):
  date 默认 today
  类型解析：显式 type → 单查；缺省 → MIDDLE 优先，无则 BUY，再无则 SELL（返回标明实际类型）
  命中 = 序列中 EFFECTIVE_DATE <= date <= EXPIRE_DATE 的记录
  命中 → {rate, effectiveDate, expireDate, sourceFileNo, rateType, baseCcy, quoteCcy}
  无命中 → {applicable:false, message:"该日期无有效汇率",
            reasons:[无该币对记录 / 区间不覆盖该日期(列出最近区间) / 类型无记录]}
  （非空结构，spec 场景要求）
```

### D5 事件与页面

- `MDM.RATE.CREATED/UPDATED`，bizCode = `CNY/USD:MIDDLE:2026-07-01`（序列键+**生效日**）→ 幂等键 `CNY/USD:MIDDLE:{生效日}:vN`
  - **实施揭示**：仅序列键时同序列多段记录 verNo 均为 1 → 撞键 409（冒烟 10/16 发现），生效日入键修复；spec 场景已同步
- cross-domain 事件流 `EVENT_TYPES` +2（跨页面任务）
- `/m/1.5.1` 单页三区：筛选列表（计算态标签）+ 新建/变更弹窗（冲突清单 Alert 展示）+ 试算面板（同一 Tab 内卡片）

### D6 API

```
GET  /api/mdm/exchange-rates                 分页（baseCcy/quoteCcy/rateType/lifecycle 筛选）
POST /api/mdm/exchange-rates                 创建
PUT  /api/mdm/exchange-rates                 变更（序列键锁定 + 历史 422）
GET  /api/mdm/exchange-rates/{id}            详情
GET  /api/mdm/exchange-rates/{id}/versions / diff   版本
GET  /api/mdm/exchange-rates/trial           试算（base/quote/date/type）
写操作 → hasRole("ADMIN")
```

## Risks / Trade-offs

- [断档严格校验对录入顺序敏感（须从最早段依次录入）] → spec 明文 L1 接受；冲突/断档响应含可操作口径（「须 ≤ 某日」）降低修正成本
- [EXPIRE_DATE 必填与现实央行数据不完全一致] → 录入方补显式段（如年底），符合区间链模型
- [左缺口为解释性扩展] → spec「区间链断点」覆盖，design 记录；偏差表不单列（同一规则的对称面）
- [序列键锁定后改键诉求] → 422 指引新建记录（同物料/客户编码锁定一致）
- [试算缺省类型回退链] → 返回标明实际类型，消费方无歧义

## Migration Plan

新增 `025-exchange-rate.sql`（2 表，IF NOT EXISTS 幂等）→ 重启 DbBootstrap → marker 025 + `SHOW TABLES` 验证。回滚 = 回退代码（新表无存量依赖）。

## Open Questions

（无 —— 粒度、append-only、键锁定、试算回退链均已决策。）
