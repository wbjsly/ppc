# Tasks

## 1. 政策快照与事件（迁移 027 + 后端）

- [x] 1.1 迁移 `027-tax-policy-version.sql`（政策版本表 IF NOT EXISTS，ENTITY_ID+VERSION_NO 唯一）+ 实体/DAO；验证：启动建表、重复启动幂等
- [x] 1.2 `MdmTaxPolicyServiceImpl` 补快照与事件：create→V1 CREATE、update→V2.. UPDATE（七字段 diff DIFF_SUMMARY、CHANGE_REASON 置空），`MDM.POLICY.CREATED/UPDATED` 幂等键 `政策文号:{ID前8位}:vN`（不含 exist=false 字段进 wrapper）；验证：curl 登记/编辑各生成快照与事件，重放同版本 409（DB 查版本表与 outbox）
- [x] 1.3 `versions(id)` + `diff(id,from,to)` 接口（compareSnapshots 复制自税码实现）；验证：curl 版本升序、diff 仅返回变更字段、未认证 401、ROLE_USER 读 200

## 2. 工作台后端（候选/预检/提交）

- [x] 2.1 `MdmTaxWorkbenchService/Controller` + `GET candidates`：非软删税码分组取链尾（计算态、链尾区间、建议生效日=尾+1）、关键字筛选、空态 hint；验证：curl 候选结构与空态
- [x] 2.2 `POST preview`：五类计划核心（①空链首段 ②尾+1 衔接 ③未失效尾段内缩短+新建 ④中间段缩短+新建自动失效日=后续段-1 ⑤阻断：早于今天/已失效历史/链首前/断档空洞）+ 字段校验（TaxCodeRules 含零税率矛盾）+ IntervalRules（模拟缩短后判定）；行内参数缺省回退统一参数、scope/calcType/rateKind 继承被选段；验证：curl 五类场景各返回预期 plan/reason，dry-run 不落库
- [x] 2.3 `POST submit`：复验计划重算、行级独立执行（缩短=`update` 原因`政策切换缩短，依据 {政策文号}`，新建=`create` 政策文号自动带入）、行内顺序与 PARTIAL 语义、FAILED/PARTIAL/SUCCESS 报告、>500 行 422、预检失败行不执行；验证：curl 部分失败报告、新段入回链与事件、缩短生成 TAXCODE.UPDATED、超限 422
- [x] 2.4 SecurityConfig 覆盖核对：`POST /api/mdm/tax-policies/{id}/workbench/**` 被既有 `tax-policies/**` ADMIN 规则覆盖（不足则补）；验证：ROLE_USER 调 preview/submit 403、admin 200、构建通过

## 3. 前端（政策页扩展）

- [x] 3.1 `api/mdm/tax-policy.js` 扩展 versions/diff/candidates/workbenchPreview/workbenchSubmit + 政策行「对比」按钮与对比抽屉（from/to 同版本禁用、逐字段红绿）；验证：vite 无错、对比交互与税码页同款
- [x] 3.2 工作台抽屉四步（el-steps）：①候选勾选表（搜索/全选/链尾与建议日列）②统一切换日+统一新段失效日+行内可改列 ③预检表格（红绿、plan 动作摘要、自动失效日回显）④提交与报告（SUCCESS/FAILED/PARTIAL 统计+明细+「查看关联变更」回链）；「发起变更」按钮 isAdmin 门控；验证：页面可达、非管理员不渲染入口
- [x] 3.3 政策页回归：台账登记/编辑/删除/关联抽屉不受影响；验证：既有交互冒烟通过

## 4. 端到端验证

- [x] 4.1 接口冒烟全量：快照/事件/重放 409、versions/diff、候选、preview 五类计划、submit 部分失败+PARTIAL 重试降级、超限 422、401/403/ROLE_USER 只读；全部断言通过
- [x] 4.2 浏览器全流程：admin → 1.6.2 登记政策+编辑出对比红绿 → 「发起变更」四步（勾选 2 税码、统一切换日落在生效尾段内 → 预检两行缩短+新建计划 → 提交 2 成功 → 报告回链看新段）→ 已失效历史阻断行演示 → zhangsan 无「发起变更」入口；截图留证、无 pageerror
- [x] 4.3 数据清理归种子态（政策/税码/版本/outbox POLICY+TAXCODE 清空）+ 兄弟回归（1.6.1/1.6.3、政策台账四操作）+ `openspec validate add-tax-policy-workbench --strict` 通过 + 勾选全部任务
