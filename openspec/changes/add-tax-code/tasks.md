# Tasks

## 1. 后端数据层与共享规则

- [x] 1.1 迁移 `026-tax-code.sql`（tax_code + tax_code_version + tax_policy 三表，IF NOT EXISTS 幂等，`(TAX_CODE, EFFECTIVE_DATE)` 与 `POLICY_NO` 索引、政策表 POLICY_NO 唯一）+ 三实体/DAO；验证：启动后 `SHOW TABLES` 三表存在、marker 记录、重复启动不报错
- [x] 1.2 共享规则：新增 `IntervalRules`（IntervalSeg record + check 五段判定原样迁移），`ExchangeRateRules.checkInterval` 改薄委托，新增 `TaxCodeRules.validateFields`（编号格式/枚举/税率精度与零税率矛盾/日期关系/政策文号必填）；验证：`mvn -DskipTests package` 通过 + curl 回归 1.5.1 区间相交/断档/首条三场景提示不变
- [x] 1.3 后端构建与安全基线：`JAVA_HOME=… mvn -q -DskipTests package` 通过；验证：Knife4j 可访问，为后续接口冒烟就绪

## 2. 税码维护接口（1.6.1）

- [x] 2.1 `MdmTaxCodeService/Impl/Controller`：分页（关键字/适用范围/计税方式/计算态筛选+lifecycle 推导）、create/update/delete（序列键=税码编号锁定 422、历史 422、原因≥2 字、`IntervalRules` 区间校验排除自身、乐观锁 409、快照 CREATE/UPDATE、`MDM.TAXCODE.*` 事件 D3 幂等键含 ID 前 8 位、`exist=false` 字段不进 wrapper）；验证：curl 422 各场景（必填/枚举/零税率矛盾/相交/断档/改号/历史）+ 200 正常 + 快照与事件入表
- [x] 2.2 `trial` + `versions` + `diff` 接口：按税码+日期取数、缺失明示非空 reasons、版本列表与两版本字段对比；验证：curl 命中/区间外/无记录三态、diff 返回变更字段、未认证 401、ROLE_USER 写 403

## 3. 政策台账接口（1.6.2）

- [x] 3.1 `MdmTaxPolicyService/Impl/Controller`：分页、create/update（文号唯一 409、文号创建后锁定 422、生效日期≥发布日期 422、审计字段）、delete（被税码引用 422 带引用计数、未引用可删）、`GET /{id}/tax-codes` 关联回链（税码清单+版本流水倒序，空时明示提示语）；验证：curl 唯一冲突/锁定/引用禁删/回链命中/空明示/403

## 4. 批量导入接口（1.6.3）

- [x] 4.1 `MdmTaxCodeBatchService/Impl/Controller`：`preview`（逐行校验+同税码批内排序衔接存量链∪前序段，返回 rowNo/valid/reason/normalized，不落库）、`batch`（方法不加 @Transactional 行级独立提交、公共政策文号回填、计税方式/税率类型缺省 GENERAL/STANDARD、>500 行 422、报告 total/succeeded/failed）；验证：curl 预检红绿、部分失败 8/2 不回滚、超限 422、每成功行有快照+事件

## 5. 前端三页面

- [x] 5.1 `api/mdm/tax-code.js` + `views/mdm/tax-code/index.vue`：列表（三计算态标签、已失效只读）、新建/变更弹窗（枚举下拉、变更原因）、试算抽层（命中/缺失明示）、版本对比（versions+diff 红绿高亮，沿 1.5.3 对比实现）；验证：vite 构建无错、页面交互与 1.5.1 同构
- [x] 5.2 `api/mdm/tax-policy.js` + `views/mdm/tax-policy/index.vue`：台账列表、新建/编辑弹窗（文号禁改）、删除（引用 422 回显）、关联税码抽屉（清单+版本流水倒序、政策未登记提示不报错）；验证：同上
- [x] 5.3 `utils/tax-import.js`（自带 8 列解析器，规则沿 rate-import 同套；税码 8 列模板/报告下载，`rate-import.js` 零改动）+ `views/mdm/tax-code-batch/index.vue`（模板→录入→预检红绿→提交→结果报告→跳转 1.6.1）+ `router/index.js` 注册 `/m/1.6.1~1.6.3`；验证：三路由页面可达、菜单点击进入、`rate-import.js` 源码零改动（`git diff`/文件未变更）

## 6. 端到端验证

- [x] 6.1 接口冒烟全量：税码 CRUD 全部 422 清单、trial 三态、versions/diff、政策唯一/锁定/禁删/回链、批量预检与部分失败报告、401/403/ROLE_USER 只读；全部断言通过
- [x] 6.2 浏览器全流程：admin 登录 → 1.6.1 造数（创建两段+变更）+试算+对比 → 1.6.2 登记政策并回链查看 → 1.6.3 CSV 粘贴预检（含故意断档行）→ 提交报告 → 结果跳转；zhangsan 只读无写入口；截图留证、全程无 pageerror
- [x] 6.3 数据清理归种子态（tax_code/version/policy/outbox TAXCODE 事件清空）+ 兄弟回归（1.5.1 区间三场景、1.5.2 解析导入、1.5.3 双 Tab 正常）+ `openspec validate add-tax-code --strict` 通过 + 勾选全部任务
