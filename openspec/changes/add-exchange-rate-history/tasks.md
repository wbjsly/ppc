# Tasks

## 1. 后端只读接口（sequence / history + 事件回显）

- [x] 1.1 `MdmExchangeRateService` + `Impl` 新增 `sequence(baseCcy, quoteCcy, rateType)`：必填 422、复用 `rateDao.selectSequence` 全量返回（含已失效、按生效日期升序、服务端计算 lifecycle），空序列返回空列表；`MdmExchangeRateController` 加 `GET /sequence`（参数缺省 422 验证：curl 带/不带参数断言 200 空链与 422）
- [x] 1.2 新增 `history(current, size, rateType, opType, keyword)`：查 `erp_mdm_exchange_rate_version` 按 CREATE_DATE 倒序分页，记录标识从 SNAPSHOT_JSON 解析（不 join 主表，软删不缺失），rateType/opType 走 SNAPSHOT_JSON LIKE、keyword 走 DIFF_SUMMARY/CHANGE_REASON LIKE；Controller 加 `GET /history`（curl 验证倒序分页 + 三类筛选 + 软删记录快照仍在）
- [x] 1.3 history 响应附事件列：收集本页各行幂等键 `BASE/QUOTE:type:生效日期:vN`，按 IDEMPOTENCY_KEY IN 批查 `erp_ops_outbox`，映射 `event:{eventType,idempotencyKey,status}`，无命中 null、批查异常降级 null 不报错（curl 断言已发事件的行有 event、无事件行为 null）
- [x] 1.4 后端构建冒烟：`JAVA_HOME=... mvn -q -DskipTests package` 通过，Knife4j 手工验证新接口 401（未认证）/ 200（admin token）

## 2. 前端历史页与对比视图

- [x] 2.1 `api/mdm/exchange-rate.js` 加 `getSequence` / `getHistory`；新增 `views/mdm/exchange-rate-history/index.vue`：区间链查询表单（币对+类型必填）+ 区间链表格（升序、计算态、来源编号、版本号、空链「该序列暂无历史」）
- [x] 2.2 时间线 Tab：分页表格（记录标识/版本号/操作类型/差异摘要/变更原因/操作人/时间/事件列）+ rateType/opType/keyword 筛选 + 事件「—」占位
- [x] 2.3 对比视图：行内 from/to 版本下拉（调既有 versions），同版本禁用；确认调既有 diff，抽屉逐字段并排、旧值红新值绿、未变字段不展示
- [x] 2.4 「按此历史值发起维护」：UPDATE 行按钮 → `router.push('/m/1.5.1?prefill=...')`（encodeURIComponent，不带生效/失效日期）；`router/index.js` 注册 `/m/1.5.3` → 新页面

## 3. 1.5.1 预填接入

- [x] 3.1 `exchange-rate/index.vue` onMounted 检测 `route.query.prefill`：解析后打开新建表单预填 币对/类型/汇率/来源编号（日期留空）并 `router.replace` 清 query；无 query 行为不变（验证：直接访问 /m/1.5.1 与带参进入两种场景）

## 4. 端到端验证

- [x] 4.1 接口冒烟全量：sequence（必填 422 / 空链 / 全链含失效）、history（倒序分页 / 三筛选 / 软删不缺失 / 事件命中与 null）、diff/versions 复用回归、未认证 401、ROLE_USER 可查
- [x] 4.2 浏览器全流程：1.5.1 造数（创建→更新得 CREATE/UPDATE 快照）→ 1.5.3 区间链 Tab 查全链 → 时间线 Tab 看倒序与事件列 → 两版本对比高亮 → 预填跳转 1.5.1 带入且日期为空 → 预填提交按 BR-4.1-17 阻断/通过 → 1.5.1 无参进入回归正常；登录 admin 验证全流程，zhangsan 验证只读可查无写入口
- [x] 4.3 数据清理归种子态（rate/version/outbox 清空）+ 兄弟模块回归（1.5.1 列表、1.5.2 批量页正常）+ `openspec validate --strict` 通过 + 勾选全部任务
