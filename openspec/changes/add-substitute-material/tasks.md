# Tasks

## 1. 后端

- [x] 1.1 迁移 `019-substitute-material.sql`：`ADD INDEX IDX_MDM_ITEM_ALT (ALT_ITEM_CODE)`；重启后端验证 marker 019 与索引存在
- [x] 1.2 `MdmItemDao` 扩展：反查 SQL（指向某编码的启用源物料 `LIMIT 10` + 总数）、替代关系列表 SQL（自 join + 方向/关键字/状态筛选 + COUNT 分页）
- [x] 1.3 `MdmItemServiceImpl`：`requireSubstituteValid(sourceId, substituteCode)` 三项校验（已发布/非自身/**间接环回溯 visited 判重 + 深度10 + 链路串**）；create/update 的 `altItemCode` 接入该校验（行 477 gap 补齐）
- [x] 1.4 `disable()` 扩展：被启用物料指向 → 409 + 引用方清单（LIMIT 10 + 总数）
- [x] 1.5 Service 新方法 `setSubstitute/clearSubstitute/substituteList`（设/清走物料版本快照，`changeReason` 自动填「设置替代/清除替代」）+ Controller 端点（`PUT/DELETE /items/{id}/substitute`、`GET /items/substitutes`）+ `SecurityConfig` 写接口 ADMIN；编译打包通过
- [x] 1.6 接口冒烟：设置替代 200+V快照 → 目标停用 422 → 自身 422 → 间接环 422+链路串（B→C→A）→ 深度>10 脏数据链 422 → 清除 200 → 反查列表含源 → 方向筛选 → 被引用停用 409+清单 → 表单 altItemCode 填停用物料 422 → 权限 403/401

## 2. 前端

- [x] 2.1 API 层 `api/mdm/substitute.js`；`views/mdm/substitute/index.vue`：替代关系列表（源→目标两列组）+ 正向/反查维度切换 + 关键字/状态筛选 + 分页 + 行内「设置替代」（目标下拉仅启用项）/「清除替代」（原因自动附）+ 失效目标红标
- [x] 2.2 `views/mdm/item/index.vue`：altItemCode 输入失焦实时校验提示 + 提交错误回显（477 gap 前端侧）；停用确认框展示替代指向/未配置给建议文案
- [x] 2.3 `router/index.js` 注册 `/m/1.2.5` → `views/mdm/substitute/index.vue`

## 3. 端到端验证

- [x] 3.1 浏览器全流程：设置替代（目标下拉仅启用）→ 反查维度切换 → 环阻断回显（含链路）→ 清除 → 停用被引用阻断回显 → 物料表单 477 校验回显 → 停用提示文案
- [x] 3.2 权限：`ROLE_USER` 设置替代 403 / 列表 200 / 未认证 401；回归 1.2.1/1.2.2/1.2.3 页面与接口无损
- [x] 3.3 测试数据清理：仅留种子物料（`RM0001000001`，`altItemCode` 为空）与 5 分类
- [x] 3.4 `openspec validate add-substitute-material --strict --no-interactive` 通过，全部任务勾选
