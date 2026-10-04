# Tasks

## 1. 后端

- [x] 1.1 全局 grep `status` 比较点（`getStatus`/`"0"`/`"1"`），核对每处查询语义（「不可引用」= `!= '1'`），列出需确认清单；确认 `MdmItem` 实体 STATUS 注释扩展为 `1 启用 / 0 停用 / 2 已归档`，无迁移脚本（验证：grep 结果逐条记录，注释已更新）
- [x] 1.2 `MdmItemService(Mpl)` 三态状态机：`disable(id, reason)`（原因必填 422 + 现有替代引用 409 + DISABLE 快照含原因）、`enable(id, reason)`（仅 0→1、替代指向目标非启用 422、ENABLE 快照）、`archive(id, reason)`（仅 0→2、有替代引用方 409、ARCHIVE 快照）；停用成功补 `MDM.ITEM.DISABLED` 日志桩（验证：编译通过）
- [x] 1.3 影响分析 `GET /api/mdm/items/{id}/impact`：返回替代引用清单+总数（真实）、下游空清单+`downstreamStub:true`+未接入说明；Controller 补 `PUT /items/{id}/enable|archive`、`POST /items/disable-batch`（`{ids, reason}` 逐条结果 `{succeeded, failed}`）（验证：编译打包通过）
- [x] 1.4 `SecurityConfig` 新增写接口（enable/archive/disable-batch）`hasRole("ADMIN")`；工作台列表分页如需 `status` 参数则扩展现有分页接口（验证：配置就位，`mvn -DskipTests package` 通过）
- [x] 1.5 接口冒烟：停用缺原因 422 → 停用成功 200+DISABLE 快照含原因 → 重复停用 422 → 被替代引用停用 409+清单 → 影响分析含替代清单+桩标记 → 批量 3 条含 1 条阻断（2 成 1 败分组返回）→ 启用 200 → 替代目标失效启用 422 → 启用中归档 422 → 归档成功 200+快照 → 归档态直接启用 422 → `MDM.ITEM.DISABLED` 日志落盘 → ROLE_USER 写 403 / 未认证 401

## 2. 前端

- [x] 2.1 `api/mdm/item-disable.js`（impact/enable/archive/batch + 复用 items 分页）；`views/mdm/item-disable/index.vue`：状态筛选（全部/启用/停用/已归档）+ 关键字 + 分页列表，行操作按状态显示（停用/启用/归档）+「影响分析」抽屉（替代清单 + 桩口径明示文案）（验证：页面可加载、抽屉两类引用展示正确）
- [x] 2.2 确认弹窗三件套：停用（原因必填+替代阻断回显）、启用（原因必填+替代目标失效回显）、归档（原因必填+终态警示文案）；批量勾选（仅启用行）→ 统一停用弹窗 → 汇总结果（成功 N/失败 M+原因）；catch 不重复提示（拦截器单点弹错）（验证：触发 422/409 弹错正确且 console 无 unhandled rejection）
- [x] 2.3 `router/index.js` 注册 `/m/1.2.4` → `views/mdm/item-disable/index.vue`；`item/index.vue` 行内停用弹窗补必填原因（对接 `disable(id, reason)` 新签名）（验证：菜单 1.2.4 切真实页，列表页停用带原因成功）

## 3. 端到端验证

- [x] 3.1 浏览器全流程：工作台打开 → 影响分析抽屉（桩说明可见）→ 批量停用（1 条阻断 2 条成功汇总回显）→ 单条停用成功列表状态变「停用」→ 启用回退 → 替代目标失效启用阻断回显 → 归档终态警示+成功 → 归档态不可直接启用回显 → 物料列表页行内停用（原因必填）→ 新业务下拉排除非启用项
- [x] 3.2 权限：`ROLE_USER` 停用/启用/归档 403、影响分析 200、未认证 401；回归 1.2.1/1.2.2/1.2.3/1.2.5 页面与接口无损（状态 2 新值不破坏既有筛选）
- [x] 3.3 测试数据清理：归档/停用物料恢复或删除至种子态（`RM0001000001` 启用、5 分类、替代关系为空、版本表清空）
- [x] 3.4 `openspec validate add-item-disable-archive --strict --no-interactive` 通过，全部任务勾选
