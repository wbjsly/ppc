# Tasks

## 1. 后端

- [x] 1.1 迁移 `021-credit-limit.sql`：`erp_mdm_customer_view` 裸 `ADD COLUMN` 四列（`TEMP_CREDIT_LIMIT`/`TEMP_EXPIRE_DATE`/`LAST_REVIEW_DATE`/`COMPRESSED_LIMIT`，013 教训注释：MySQL 无 IF NOT EXISTS，靠 marker 幂等）；重启后端验证 marker 021 与 `SHOW COLUMNS` 四列存在（验证：列清单含 4 新列）
- [x] 1.2 实体 `MdmCustomerView` 加 4 字段；`CreditLimitSupport` 组件抽取（`requireCreditSumWithinGroup` 求和校验 + `effectiveLimit` 口径 D3，准入 Service 切换引用）；编译通过（验证：`mvn package` 通过且准入冒烟（求和 422）不回归）
- [x] 1.3 `MdmCreditService/Impl`：法人额度调整（常规求和 422 / 临时有效期 > 当天校验 / 原因必填 / UPDATE 快照含 diff + `MDM.CUSTOMER.CREDIT_UPDATED` 日志桩 / 已合并客户 422）、集团基准调整（总额度/评级 + 快照）、占用率计算（总额度空展示未配置）、复审通过（更新日期+清压缩+快照）（验证：编译通过）
- [x] 1.4 复审超期懒压缩（超 12 月 && `COMPRESSED_LIMIT IS NULL` → 写 `CREDIT_LIMIT×ratio` 入快照，建档不足 12 月宽限，幂等）+ 到期回滚懒校验 + `MdmCreditScheduler`（`@EnableScheduling` + `@Scheduled` 每小时 sweep `TEMP_EXPIRE_DATE<=CURDATE()` → 清临时入快照+日志，try/catch 不阻塞）（验证：编译通过）
- [x] 1.5 `MdmCreditController`（`/api/mdm/credit-limits`：view-limit/group-limit/review/occupancy/timeline/trial）+ 时间轴（按集团聚合各视图额度类快照）+ 试算（有效额度 + 应收/未清SO 桩标注）+ `SecurityConfig` 写接口 ADMIN；编译打包通过
- [x] 1.6 接口冒烟：法人调常规 200 → 求和超限 422 → 临时额度无有效期 422 → 有效期≤今天 422 → 临时 200（试算含临时）→ 手改 DB 过期 + 扫描回滚 200（快照+日志）→ 集团总额度调整 200 → 占用率 85% 标记 → 总额度空「未配置」→ 构造 13 月前复审日 → 查询触发压缩 200（COMPRESSED_LIMIT 写入）→ 复审通过恢复 200 → 新客户宽限不压缩 → 时间轴 200（额度 diff+原因）→ 试算桩标注 200 → 已合并客户调整 422 → ROLE_USER 写 403 / 未认证 401

## 2. 前端

- [x] 2.1 `api/mdm/credit-limit.js`；`views/mdm/credit-limit/index.vue`：集团列表（评级/总额度/Σ法人/**占用率>80% 黄标**/复审超期红标计数/状态）+ 展开法人子表（常规/临时+到期倒计时/当前有效额度/复审日期）+ 筛选分页（验证：页面加载、占用率与红黄标展示正确）
- [x] 2.2 额度调整弹窗（常规+临时+有效期校验 + **求和/口径双分区提示** + 原因必填）+ 集团基准调整弹窗 + 「复审通过」确认（更新日期恢复快照回显）+ 可用额度试算区（公式 + 应收/未清SO「未接入不计入」标注，随临时/压缩实时变化）（验证：三类校验前端回显、试算随状态变化）
- [x] 2.3 时间轴抽屉（额度类快照时间轴 + 两版本对比）+ `router/index.js` 注册 `/m/1.3.2` + **`views/mdm/customer/index.vue` 法人视图额度字段改只读**（提示「额度请在信用额度菜单调整」）（验证：菜单切真实页、准入页额度只读提示可见）
- [x] 2.4 到期回滚/复审压缩懒校验前端验证：构造过期临时额度与 13 月复审日期 → 刷新页面即见回滚/压缩生效（验证：无需等定时任务，页面口径正确）

## 3. 端到端验证

- [x] 3.1 浏览器全流程：新建客户+挂法人 → 调常规额度（求和超限红字→调整通过）→ 设临时额度（无有效期拦截→过期日拦截→成功，试算含临时）→ 改 DB 到期+刷新页面懒回滚 → 集团总额度调整（占用率>80% 黄标）→ 总额度清空「未配置」→ 复审超期红标+压缩回显 → 复审通过恢复 → 时间轴额度 diff+原因 → 试算桩标注 → console 无 error/warn
- [x] 3.2 权限与回归：`ROLE_USER` 三写 403、查询 200、未认证 401；回归 1.3.1 客户准入（建档/法人弹窗额度只读改造/合并/状态机）与 1.2.1 物料页面接口无损
- [x] 3.3 测试数据清理：客户 4 表 + 加列字段归零（空客户 4 表、法人 1、物料种子、0 版本、5 分类）
- [x] 3.4 `openspec validate add-credit-limit --strict --no-interactive` 通过，全部任务勾选
