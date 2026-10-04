# Tasks

## 1. 后端

- [x] 1.1 **校验核心重构**（design D2）：`MdmExchangeRateServiceImpl` 的 `requireFields`/`requireNoGapOrOverlap` 抽为可复用核心（接收区间列表的纯函数 + 返回原因；单条路径薄包装抛异常），`selectSequence` 支持传入额外批内区间合并判定；编译通过且 **1.5.1 单条冒烟回归**（区间相交/断档/必填/ISO 抽样 5 场景 422 文案不变）
- [x] 1.2 `MdmExchangeRateBatchService/Impl`：`preview`（归一化公共来源编号 → 批内分组按生效日排序 → 逐行字段+衔接校验 → `{rowNo, valid, reason}`）；`batch`（>500 行 422 整批拒；**方法不加 @Transactional** 使行级 create 各自独立提交；同排序执行；逐行 catch 收集 `{rowNo, result, reason}`）（验证：编译通过）
- [x] 1.3 `MdmExchangeRateBatchController`（`/api/mdm/exchange-rate-batch/preview|batch`，body `{rows, defaultSourceFileNo}`）+ `SecurityConfig` 写接口 ADMIN；编译打包通过
- [x] 1.4 接口冒烟：preview 5 行全绿 → 缺来源编号行红（无公共值）→ 公共值回填后绿 → 批内同序列断档行红（首行绿）→ 与存量链断档行红（含口径）→ batch 5 行 1 失败（2 成 1 败分组、成功行快照+CREATED 事件落库、失败行无快照）→ 重复提交同批（已成功行 409 计失败、失败行重试成功）→ 501 行 422 → 空数组 422 → ROLE_USER preview/batch 403 / 未认证 401 → 1.5.1 回归（分页/试算 200）

## 2. 前端

- [x] 2.1 `api/mdm/exchange-rate-batch.js`；**解析器** `parseRows`（BOM 剥离/换行归一/Tab 或逗号探测/跳表头空行 #注释/列数校验/公共来源编号回填）+ 单测式内联断言不可行则以冒烟覆盖（验证：五种畸形输入解析行为正确——BOM、Tab、表头、空行、列不足）
- [x] 2.2 页面四区（design D5）：模板下载（Blob+BOM+说明行）/ 上传与粘贴双入口 + 公共来源编号 / 预检表格红绿标+原因列+「提交（N 行将失败）」动态文案 / 结果报告统计卡+失败标红+报告 CSV 下载+跳转 1.5.1（验证：全流程按钮态与文案正确）
- [x] 2.3 `router/index.js` 注册 `/m/1.5.2`（验证：菜单切真实页）

## 3. 端到端验证

- [x] 3.1 浏览器全流程：下载模板 → 构造 CSV（3 绿 1 缺来源 1 断档）上传 → 预检红绿标与原因回显 → 补公共来源编号重新预检（缺来源转绿）→ 提交 → 结果报告（3 成 2 败或按构造）+ 失败标红 → 下载报告 CSV 内容核对 → 跳转 1.5.1 见成功行 → 事件流筛 `MDM.RATE.CREATED`（批量新增键）→ console 无 error/warn
- [x] 3.2 权限与回归：`ROLE_USER` preview/batch 403、未认证 401；回归 1.5.1 页面与接口（单条创建/区间校验/试算）、1.3.3 事件流无损
- [x] 3.3 测试数据清理：汇率 2 表与 outbox 清空；全部业务表与物料/分类归种子态（1 物料启用、5 分类、法人 1、业务表全空、outbox 空）
- [x] 3.4 `openspec validate add-exchange-rate-batch --strict --no-interactive` 通过，全部任务勾选
