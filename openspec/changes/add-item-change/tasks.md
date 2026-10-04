# Tasks

## 1. 后端

- [x] 1.1 迁移 `017-item-change-audit.sql`：`erp_mdm_item_version` `ADD COLUMN CHANGE_REASON VARCHAR(255)` + `CHANGE_TYPE VARCHAR(16)`；重启后端验证 marker 017 与列存在
- [x] 1.2 `MdmItemVersion` 实体加两字段；`MdmItem` 加 transient `changeReason`（`@TableField(exist=false)`）；编译通过（JDK 17）
- [x] 1.3 `MdmItemServiceImpl.update`：`changeReason` 必填（≥2字，422）；**无差异提交 422「无变更内容」**；服务端按差异集与 {baseUnit, categoryCode, purchaseType} 交集计算 `changeType` 落库；版本历史返回含 reason/type；`MDM.ITEM.UPDATED` 与 BOM 引用桩标 TODO；编译打包通过
- [x] 1.4 接口冒烟：无原因 422 → 无差异 422 → 改采购类型 → 版本记录 `CHANGE_TYPE=CRITICAL` + reason 落库 → 改名称 → `GENERAL` → 版本历史展示 reason/type → 编码修改 422（含跳转语义）→ 旧 verNo 409

## 2. 前端

- [x] 2.1 `views/mdm/item/index.vue` 变更弹窗增强：**变更原因必填**、**新旧值差异预览区**（打开时快照 vs 表单值，同 diffSummary 字段集，空差异拦截）、**关键属性警示标签**（命中三项实时显示）、编码修改阻断后**「去新建」预填可继承白名单**（名称/分类/单位/物料组/采购类型/存储条件/批次/保质期/包装规格/条码/替代物料，编码留空）
- [x] 2.2 1.2.2 入口页：`route.meta.mode='change'`（1.2.2 隐藏新建、突出「发起变更」；1.2.1 保持现状）；`router/index.js` 注册 `/m/1.2.2` → 复用 item 组件
- [x] 2.3 版本历史列表展示 `changeReason` + `changeType`（CRITICAL 红标/GENERAL 灰标）

## 3. 端到端验证

- [x] 3.1 浏览器全流程（在 /m/1.2.2）：发起变更 → 缺原因拦截 → 改采购类型出警示标签 + 差异预览 → 保存 → 版本历史见 reason + CRITICAL 红标 → 改名称类一般字段 → GENERAL → 无差异提交拦截
- [x] 3.2 编码阻断跳转：变更中改编码 → 阻断提示「去新建」→ 点击 → 新建弹窗预填可继承字段且编码为空
- [x] 3.3 权限：`ROLE_USER` 变更 403；未认证 401；回归 1.2.1 页面与其余模块 API 无损；测试数据清理仅留种子
- [x] 3.4 `openspec validate add-item-change --strict --no-interactive` 通过，全部任务勾选
