# Design

## Context

见 proposal.md（Why）。现状：客户域零代码零表；菜单 `M1-3-1 客户准入 /m/1.3.1` 已在 099 种子中（无需菜单迁移），router 指向占位页。可复用的既有模式：

- 三段式/两段式编码与查重：`MdmItemServiceImpl` 的自动编码（`MAX(ITEM_CODE)` 流水）、名称编辑距离 ≤3（Levenshtein + `findSimilarCandidates` LIKE 预筛）
- 版本快照：`saveSnapshot(entity, opType, diffSummary, reason, changeType)` 模式（实体各自维护版本表）
- 乐观锁：`BaseEntity.verNo` + `updateById` 返回 0 → 409
- 分层：Controller → Service 接口 → ServiceImpl → DAO(MyBatis-Plus) → Entity；`R<T>` 包装、业务码在 body
- 权限：`SecurityConfig` 写接口 `hasRole("ADMIN")`；401 JSON entry point

## Goals / Non-Goals

**Goals:**
- 两级模型一次建对：集团/法人字段分层、额度求和校验先行落地（供 1.3.2 复用）
- 状态机含合并终态，编码锁定不可逆
- 为下游（SO 引用阻断、事件总线）留与物料域同款的桩口径

**Non-Goals:**
- 额度调整界面与临时额度/占用率提示（FR-4.1-6-3、BR-4.1-32/33 → 1.3.2）
- 跨域共享视图（1.3.3）、外部征信接入、评级复评调度
- 合并 30 天回退、双人复核流（偏差已记录）
- 客户编码手工指定（统一自动编码，与法人/成本中心一致）

## Decisions

### D1 表模型：集团表 + 法人视图表 + 各自版本表（4 张）
```sql
erp_mdm_customer_group          -- 集团视图
  ID PK, CUSTOMER_CODE UNIQUE(CUST-NNNN), CUSTOMER_NAME, TAX_NO,
  USCC(统一社会信用代码), CREDIT_RATING, CREDIT_LIMIT_TOTAL,
  STATUS('1'启用/'0'停用/'2'冻结/'3'已合并), MERGED_TO(合并指向编码),
  VER_NO, DEL_FLAG, 审计字段
erp_mdm_customer_view           -- 法人视图
  ID PK, GROUP_ID FK→集团, LEGAL_ENTITY_ID(挂法人主体，参照 erp_mdm_legal_entity),
  SHIP_ADDRESS, CONTACT_NAME, CONTACT_PHONE, PAYMENT_TERMS,
  CREDIT_LIMIT(本法人额度，可空), STATUS('1'启用/'0'停用/'2'冻结),
  VER_NO, DEL_FLAG, 审计字段
erp_mdm_customer_group_version / erp_mdm_customer_view_version
  ENTITY_ID+VERSION_NO 唯一, SNAPSHOT_JSON, DIFF_SUMMARY, OP_TYPE, CHANGE_REASON
```
- **理由**：两级结构天然是主从表；BR-4.1-31 求和校验在集团内跨法人视图聚合，单表自引用（`parent_id`）会把两种语义混在一个状态机里
- **合并改挂**：法人视图 `GROUP_ID` UPDATE 即可，无需数据迁移工具
- **状态语义**：集团 `1/0/2/3`；法人视图只有 `1/0/2`（"已合并"是集团级终态，法人视图被改挂后仍启用）
- **STATUS 加入 `MERGED='3'`** 与物料 `1/0/2` 不同——物料域无合并态，此处独立扩展，注释写明

### D2 CUST-NNNN 编码生成
- `SELECT MAX(CUSTOMER_CODE) FROM erp_mdm_customer_group` → 取尾 4 位 +1，格式化 `%04d`；无记录从 `CUST-0001` 起
- **不支持手工编码**（与法人/成本中心一致，简化表单与唯一性校验）
- 并发：同物料域现状（无分布式锁，接受 MAX+1 的低并发竞争风险，项目基线一致）

### D3 查重：复制 Levenshtein 工具而非跨域依赖
- `MdmItemServiceImpl` 的编辑距离与相似候选预筛是 private 方法；客户域**复制一份**到 `MdmCustomerGroupServiceImpl`（或抽 `common/TextSimUtil` 静态工具）
- **选静态工具类**：`com.erp.common.SimilarityUtil.levenshtein(a,b)` + `similarCandidates(pool, name, dist)`，物料与客户两处调用，消除第三份复制
- 查重口径：税号精确（`TAX_NO` 唯一性软查——非空即比对）+ 名称编辑距离 ≤3；命中返回最近 3 条
- **税号硬阻断**：查到其它集团视图 `TAX_NO` 相同 → 422「税号已存在，请先走客户合并（目标：CUST-xxxx）」；名称相似仅提示（forceCreate + dupNote 确认放行，同物料）

### D4 额度求和校验（BR-4.1-31）
- 落点：法人视图 create/update 后置校验（事务内）：`SUM(CREDIT_LIMIT) × ratio ≤ CREDIT_LIMIT_TOTAL`
- `GROUP_CREDIT_LIMIT_RATIO` 默认 100%：常量即可，`application.yml` 可覆盖（`app.mdm.group-credit-limit-ratio: 1.0`）——不过度设计参数中心
- **集团总额度为空 → 放行 + 响应提示**（规格未定义未配置行为，选择宽松侧避免阻断建档主流程）
- 聚合范围：`GROUP_ID = 本集团 AND DEL_FLAG='0'`（含停用/冻结视图的额度？—— **含**：额度是分配事实，停用不释放额度，注释写明该口径）

### D5 状态机与冻结级联
```
集团:  1(启用) ⇄ 0(停用)   1⇄2(冻结,级联法人)   1→3(合并,终态)   0→3 允许吗?
```
- 合并源允许 `1` 或 `0` → `3`（停用客户也可被合并，现实场景）；`2`(冻结) → `3` **允许**（合并处理信用问题客户是动机之一）
- `3` 为终态：任何出边拒绝（422「已合并为终态」）
- 冻结/解冻：UPDATE 集团 + `UPDATE view SET STATUS='2' WHERE GROUP_ID=? AND STATUS='1'`（解冻反向，只动 `2→1`，不覆盖停用的法人视图）
- 停用：集团停用**不级联**法人视图状态（BR-4.1-34 只定义冻结级联；法人视图随集团停用自然不可引用——引用检查走集团状态）
- 下游 SO 引用阻断：`queryCustomerReferences(id)` 空桩 + `downstreamStub:true`（与物料 impact 同款）

### D6 合并执行（单事务）
```
merge(sourceId, targetId, reason):
  校验 source≠target、source.status≠'3'、reason 必填
  冲突预检：源与目标的法人视图若有同法人主体（UK_GROUP_LE 冲突）→ 422 列出冲突清单，
            须先调整目标侧或源侧视图再合并（实现揭示：无冲突时改挂才可行）
  影响面 = COUNT(view WHERE GROUP_ID=sourceId)
  ① 法人视图改挂: UPDATE view SET GROUP_ID=targetId WHERE GROUP_ID=sourceId
  ② 源客户: STATUS='3', MERGED_TO=target.code  (乐观锁)
  ③ 快照: source MERGE / target MERGE（diff 含改挂数量与原因）
  ④ 日志桩 MDM.CUSTOMER.MERGED
```
- **无回退**：终态设计，回退需逆向 SQL，Non-Goal
- 目标客户冻结/停用 → **允许**（合并进冻结主体是合法诉求，冻结语义由目标自身状态表达）；如需阻断可在实施中与用户确认——默认放行并记 design

### D7 权限分层的降级口径
- BR-4.1-04 要求集团字段仅集团管理员、法人字段仅法人管理员——**项目无对应角色**，降级为统一 `hasRole("ADMIN")`（与全部 MDM 写接口一致），偏差表记录
- 法人视图的 `LEGAL_ENTITY_ID` 必填并参照校验（`erp_mdm_legal_entity` 启用项），借用「法人」约束代替角色分层的弱化版

### D8 前端形态
```
/m/1.3.1 views/mdm/customer/index.vue
  工具栏: [新建集团]  关键字 | 状态(启用/停用/冻结/已合并)
  集团列表: 编码 | 名称 | 税号 | 评级 | 总额度 | 法人数 | 状态 | 操作
  行操作: 详情(版本) | 变更 | 法人视图 | 停用/启用 | 冻结/解冻 | 合并
  · 法人视图: 弹窗内子表格（增改 + 额度求和实时提示 + 冻结联动标注）
  · 合并: 弹窗（目标搜索=相似候选 + 影响面 + 原因必填）
  · 详情抽屉: 描述 + 版本历史 + 逐字段对比（复用物料详情结构）
```

## Risks / Trade-offs

- [额度校验口径含停用视图 → 停用后额度仍占用] → 是刻意选择（额度=分配事实），design D4 注释固化；1.3.2 做调整时可复议
- [MAX+1 并发重号] → 与全项目编码生成一致，低并发可接受；`CUSTOMER_CODE` 唯一索引兜底 500 → 实施时捕获唯一冲突转 409 友好提示
- [复制/抽取编辑距离工具] → 选 `common/SimilarityUtil`，物料侧调用点一并切换（回归物料查重）
- [合并允许目标为冻结态] → 记录为宽松口径；若用户期望阻断，proposal 偏差表补充
- [集团总额度未配置时求和校验形同虚设] → 放行+提示是规格空白的主动选择，spec 场景已固化「未配置放行」

## Migration Plan

新增 `020-customer-admission.sql`（4 张表，`CREATE TABLE IF NOT EXISTS`，幂等）；重启后端 DbBootstrap 执行，验证 marker 020 与表存在。回滚 = 回退代码（新表无存量数据依赖）。

## Open Questions

（无 —— 额度口径、合并目标状态、编码规则均已在 design 决策中落定并经需求沟通确认。）
