# Tasks

## 1. 后端字段集退役

- [x] 1.1 `MdmLegalEntityServiceImpl`：`SNAPSHOT_FIELDS` 移除 `costCenterCode,profitCenterCode`，`buildDiff`/`readField` 同步删除两分支；编译验证通过（JDK 17 `mvn -DskipTests package`）
- [x] 1.2 接口冒烟：对存量主体（两列有值）仅改名称保存 → 返回 200 且 `diffSummary` **不含** `costCenterCode`/`profitCenterCode`；版本对比含旧字段的历史快照正常返回不报错

## 2. 前端标签组改造

- [x] 2.1 `legal-entity/index.vue` 变更表单：两处 `<el-input>` 替换为只读标签组（`el-tag`，显示 `code name`），打开表单时按 `form.id` 调 `cost-centers/options?legalEntityId=` 与 `profit-centers/options?legalEntityId=` 全量加载；新建（无 id）显示「暂无关联」提示文案
- [x] 2.2 详情抽屉：两处单值展示改为同源反查标签组（与表单一致）；提交体不再携带两字段
- [x] 2.3 浏览器验证：编辑现有主体 → 两栏标签组显示其名下全部启用中心（造 2 成本中心 + 2 利润中心场景，全量显示）；新建表单 → 两栏提示文案；中心侧新增一个中心后重开表单 → 标签自动增加

## 3. 端到端验证

- [x] 3.1 回归：法人主体 新建/变更/停用/版本对比 全流程无损；成本中心、利润中心两模块页面正常（未改动确认）
- [x] 3.2 `openspec validate add-legal-entity-center-backfill --strict --no-interactive` 通过，全部任务勾选
