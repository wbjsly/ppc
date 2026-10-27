#!/usr/bin/env node
/**
 * 解析 docs/design/08-erp-menu.md 的三级菜单清单，生成菜单种子迁移脚本。
 * 用法：node backend/scripts/generate-menu-seed.cjs
 * 输出：backend/src/main/resources/db/mysql/099-menu-seed.sql（幂等，勿手工编辑）
 */
const fs = require('fs')
const path = require('path')

const ROOT = path.resolve(__dirname, '../..')
const DOC = path.join(ROOT, 'docs/design/08-erp-menu.md')
const OUT = path.join(ROOT, 'backend/src/main/resources/db/mysql/099-menu-seed.sql')

// 一级菜单图标（Element Plus 图标名）；08 文档第 12 域正文标题为"SCM协同"，按附录一"菜单名称（四字）"取"外部协同"
const DOMAIN_ICON = {
  1: 'Collection', 2: 'ShoppingCart', 3: 'Sell', 4: 'Box', 5: 'SetUp',
  6: 'CircleCheck', 7: 'Files', 8: 'Money', 9: 'Tickets', 10: 'User',
  11: 'Folder', 12: 'Share', 13: 'Iphone', 14: 'DataAnalysis', 15: 'Grid',
  16: 'Setting', 17: 'Switch', 18: 'Tools'
}
const DOMAIN_TITLE_OVERRIDE = { 12: '外部协同' }
// 系统管理域（含新增菜单管理入口）整体限管理员可见
const ADMIN_DOMAIN = 16

const esc = (s) => String(s == null ? '' : s).replace(/'/g, "''")

const lines = fs.readFileSync(DOC, 'utf8').split(/\r?\n/)
const nodes = []
let domain = null      // 当前一级 { id, code }
let mod = null         // 当前二级 { id, code }

for (const line of lines) {
  let m
  if ((m = line.match(/^## (\d+)\. (.+)$/))) {
    const num = Number(m[1])
    const title = DOMAIN_TITLE_OVERRIDE[num] || m[2].trim()
    domain = { id: `M${num}`, code: String(num) }
    mod = null
    nodes.push({ id: domain.id, parentId: '', code: domain.code, title, sort: num * 10, level: 1 })
  } else if ((m = line.match(/^- \*\*(\d+)\.(\d+) (.+?)\*\*\s*$/)) && domain) {
    const num = Number(m[2])
    const code = `${m[1]}.${m[2]}`
    if (Number(m[1]) !== Number(domain.code)) continue // 防御：编号与当前域不符则忽略
    mod = { id: `M${m[1]}-${m[2]}`, code }
    nodes.push({ id: mod.id, parentId: domain.id, code, title: m[3].trim(), sort: num * 10, level: 2 })
  } else if ((m = line.match(/^\s+- (\d+)\.(\d+)\.(\d+) (.+)$/)) && domain) {
    if (!mod || Number(m[1]) !== Number(domain.code) || `${m[1]}.${m[2]}` !== mod.code) continue
    nodes.push({
      id: `M${m[1]}-${m[2]}-${m[3]}`,
      parentId: mod.id,
      code: `${m[1]}.${m[2]}.${m[3]}`,
      title: m[4].trim(),
      sort: Number(m[3]) * 10,
      level: 3
    })
  }
}

// ---- 校验 ----
const byId = new Map(nodes.map((n) => [n.id, n]))
if (byId.size !== nodes.length) throw new Error('存在重复 ID')
for (const n of nodes) {
  if (n.parentId && !byId.has(n.parentId)) throw new Error(`上级不存在: ${n.id} -> ${n.parentId}`)
}
const l1 = nodes.filter((n) => n.level === 1)
const l2 = nodes.filter((n) => n.level === 2)
const l3 = nodes.filter((n) => n.level === 3)
if (l1.length !== 18) throw new Error(`一级菜单应为 18 个，实际 ${l1.length}`)

// ---- 工程新增节点（08 文档之外的导航脚手架） ----
const extra = [
  { id: 'M-HOME', parentId: '', code: '', title: '工作台', path: '/dashboard', icon: 'HomeFilled', sort: 0 },
  { id: 'M16-10', parentId: 'M16', code: '16.10', title: '菜单管理', path: '/system/menu', icon: 'Menu', sort: 100 }
]
const extraIds = new Set(extra.map((e) => e.id))
for (const e of extra) {
  if (byId.has(e.id)) throw new Error(`新增节点 ID 冲突: ${e.id}`)
  if (e.parentId && !byId.has(e.parentId)) throw new Error(`新增节点上级不存在: ${e.id}`)
}

// ---- 拼装行 ----
// 工程新增的非默认 PERM（迁移与种子必须同口径，避免重建库时丢失）
// add-sales-lead-to-cash design D15：销售域角色分层；父节点必须与子节点同权限，
// 否则前端 SidebarMenu 的 map[parentId] 判定会把子节点提升为顶级孤儿
const PERM = {
  // 3.1 商机 / 3.2 报价 / 11.1 线索：销售与销售经理
  sales: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_ADMIN',
  // 3.5 订单 / 3.6 价格折扣：加销售总监
  order: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_SALES_DIRECTOR,ROLE_ADMIN',
  // 3.3 信用
  credit: 'ROLE_CREDIT_ADMIN,ROLE_FINANCE_MGR,ROLE_ADMIN',
  // 3.4 ATP / 3.7 发货：销售 + 仓库
  atp: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_WAREHOUSE,ROLE_ADMIN',
  ship: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_WAREHOUSE,ROLE_ADMIN',
  // 3.8 开票核销 / 11.11 收款侧
  invoice: 'ROLE_FINANCE_MGR,ROLE_ADMIN',
  // 3.9 返利
  rebate: 'ROLE_SALES_MGR,ROLE_SALES_DIRECTOR,ROLE_FINANCE_MGR,ROLE_ADMIN',
  // 3.10 退货
  ret: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_FINANCE_MGR,ROLE_WAREHOUSE,ROLE_ADMIN',
  // 3.11 框架合同
  fw: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_SALES_DIRECTOR,ROLE_WAREHOUSE,ROLE_ADMIN',
  // 11.11 合同管理
  contract: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_FINANCE_MGR,ROLE_ADMIN',
  // 4.1.1 仓库档案
  warehouse: 'ROLE_WAREHOUSE,ROLE_ADMIN',
  // 4.9 冻结管理（change add-freeze-management-menus）
  freezeQuality: 'ROLE_QUALITY_ENG,ROLE_QUALITY_MGR,ROLE_ADMIN',
  freezeFinance: 'ROLE_FINANCE,ROLE_FINANCE_MGR,ROLE_ADMIN',
  unfreezeApprove: 'ROLE_QUALITY_MGR,ROLE_FINANCE_MGR,ROLE_ADMIN',
  // 4.10 效期管理 4.10.3 质量评估（change add-expiry-management-menus；4.10.1/4.10.2 用 warehouse）
  expiryEval: 'ROLE_QUALITY_ENG,ROLE_QUALITY_MGR,ROLE_ADMIN',
  // 5.1 物料清单（change add-bom-management：工艺工程师维护、工艺主管审核发布）
  process: 'ROLE_PROCESS_ENG,ROLE_PROCESS_MGR,ROLE_ADMIN',
  // 一级域 M3：取其下各组权限并集，避免非授权角色看到空的销售管理域
  domain3: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_SALES_DIRECTOR,ROLE_CREDIT_ADMIN,ROLE_FINANCE_MGR,ROLE_WAREHOUSE,ROLE_ADMIN',
  // M5-4 工单管理：计划员 + 计划主管（审批签署）+ 管理员（change add-work-order-management）
  planner: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_ADMIN',
  // M5-5 齐套检查：计划两方 + 仓库（到货/缺料相关方，偏差 D1 无采购员/车间主管角色）
  kitting: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_WAREHOUSE,ROLE_ADMIN',
  // M5-6 派工管理 5.6.1/5.6.2：计划两方 + 车间主管 + 管理员（change add-dispatch-management）
  dispatch: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_SHOP_FLOOR,ROLE_ADMIN',
  // M5-6-3 外协建议：车间主管不参与外协决策（outsourcing-dispatch 权限矩阵）
  outsource: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_ADMIN',
  // M5-7-1 报工录入：车间操作工作业台（change add-reporting-management）
  reportEntry: 'ROLE_SHOP_FLOOR,ROLE_ADMIN',
  // M5-7-2 报工管理：计划两方（记录/冲销/完工/预警），操作工不参与管理动作
  reportAdmin: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_ADMIN',
  // M5-8-1 返工工单：计划两方（建单/路线/转在制/进度），质量侧不建单（change add-rework-management）
  reworkEntry: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_ADMIN',
  // M5-8-2 返修处理：质量两方+管理员+计划员只读（复检/审批/报废/入库执行侧）
  reworkAdmin: 'ROLE_QUALITY_ENG,ROLE_QUALITY_MGR,ROLE_ADMIN,ROLE_PLANNER',
  // 16.11 工作日历：管理员维护、计划员只读（capacity-load 日历菜单权限）
  calendar: 'ROLE_ADMIN,ROLE_PLANNER',
  // M5-9 试产管理 5.9.1~5.9.3：计划两方 + 工艺两方 + 项目经理 + 管理员
  // 三叶为同一阶段视图按 TRIAL_STAGE 过滤（change add-trial-production-management，spec trial-order 菜单需求/D9）
  trial: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_PROCESS_ENG,ROLE_PROCESS_MGR,ROLE_PM,ROLE_ADMIN',
  // M7 资产管理（change add-asset-capitalization）：资产管理员 + 财务资产会计
  eam: 'ROLE_ASSET_ADMIN,ROLE_FIN_ASSET,ROLE_ADMIN',
  // M7-3 资产运维（change add-asset-maintenance）：设备工程师 + 设备主管（报修全员开放不设门槛）
  maint: 'ROLE_EQUIP_ENG,ROLE_EQUIP_MGR,ROLE_ASSET_ADMIN,ROLE_ADMIN'
}
const leaf = (ids, perm) => Object.fromEntries(ids.map(id => [id, perm]))
const EXTRA_PERM = {
  'M2-8-3': 'ROLE_INTF_OPS,ROLE_ADMIN',
  'M2-9-1': 'ROLE_PM,ROLE_FINANCE_MGR,ROLE_GM,ROLE_ADMIN',
  'M2-9-2': 'ROLE_PM,ROLE_FINANCE_MGR,ROLE_GM,ROLE_ADMIN',
  'M2-9-3': 'ROLE_PM,ROLE_FINANCE_MGR,ROLE_GM,ROLE_ADMIN',
  ...leaf(['M3'], PERM.domain3),
  ...leaf(['M3-1', 'M3-1-1', 'M3-1-2', 'M3-1-3'], PERM.sales),
  ...leaf(['M3-2', 'M3-2-1', 'M3-2-2', 'M3-2-3'], PERM.sales),
  ...leaf(['M3-3', 'M3-3-1', 'M3-3-2', 'M3-3-3'], PERM.credit),
  ...leaf(['M3-4', 'M3-4-1', 'M3-4-2'], PERM.atp),
  ...leaf(['M3-5', 'M3-5-1', 'M3-5-2', 'M3-5-3', 'M3-5-4'], PERM.order),
  ...leaf(['M3-6', 'M3-6-1', 'M3-6-2', 'M3-6-3', 'M3-6-4'], PERM.order),
  ...leaf(['M3-7', 'M3-7-1', 'M3-7-2', 'M3-7-3', 'M3-7-4'], PERM.ship),
  ...leaf(['M3-8', 'M3-8-1', 'M3-8-2', 'M3-8-3'], PERM.invoice),
  ...leaf(['M3-9', 'M3-9-1', 'M3-9-2', 'M3-9-3'], PERM.rebate),
  ...leaf(['M3-10', 'M3-10-1', 'M3-10-2', 'M3-10-3'], PERM.ret),
  ...leaf(['M3-11', 'M3-11-1', 'M3-11-2'], PERM.fw),
  ...leaf(['M11-1', 'M11-1-1', 'M11-1-2'], PERM.sales),
  ...leaf(['M11-11', 'M11-11-1', 'M11-11-2', 'M11-11-3'], PERM.contract),
  // M4-1 仓库管理 4.1.1/4.1.2/4.1.3（change add-warehouse-zone-management 统一口径；父节点保持开放避免孤儿）
  ...leaf(['M4-1-1', 'M4-1-2', 'M4-1-3'], PERM.warehouse),
  // M4-2 批次管理 4.2.1/4.2.2（change add-batch-serial-master，与仓库组同口径）
  ...leaf(['M4-2-1', 'M4-2-2'], PERM.warehouse),
  // M4-3 库存快照 4.3.1/4.3.2/4.3.3（change add-stock-snapshot-three-state，菜单管可见、接口管可为）
  ...leaf(['M4-3-1', 'M4-3-2', 'M4-3-3'], PERM.warehouse),
  // M4-4 入库管理 4.4.1~4.4.4（change add-stock-posting-engine，作业台四路由）
  ...leaf(['M4-4-1', 'M4-4-2', 'M4-4-3', 'M4-4-4'], PERM.warehouse),
  // M4-4-5 仓位分配（change add-bin-assignment，菜单管可见、接口管可为）
  ...leaf(['M4-4-5'], PERM.warehouse),
  // M4-5 出库管理 4.5.1~4.5.4（change add-outbound-workbench，出库作业台四路由）
  ...leaf(['M4-5-1', 'M4-5-2', 'M4-5-3', 'M4-5-4'], PERM.warehouse),
  // M4-12 调拨管理 4.12.1 仓间调拨 / 4.12.2 在途跟踪（change add-outbound-workbench P1）
  ...leaf(['M4-12-1', 'M4-12-2'], PERM.warehouse),
  // M4-6 出库策略 4.6.1 先进先出 / 4.6.2 效期优先 / 4.6.3 拣货推荐（change add-outbound-strategy）
  ...leaf(['M4-6-1', 'M4-6-2', 'M4-6-3'], PERM.warehouse),
  // M4-7 拣货复核 4.7.1 拣货任务 / 4.7.2 扫码确认 / 4.7.3 出库复核 / 4.7.4 差异处理（change add-picking-review）
  ...leaf(['M4-7-1', 'M4-7-2', 'M4-7-3', 'M4-7-4'], PERM.warehouse),
  // M4-8 波次管理 4.8.1 波次拣货 / 4.8.2 集货发运（change add-wave-management）
  ...leaf(['M4-8-1', 'M4-8-2'], PERM.warehouse),
  // M4-9 冻结管理 4.9.1 质量冻结 / 4.9.2 财务冻结 / 4.9.3 解冻审批 / 4.9.4 影响通知（change add-freeze-management-menus）
  ...leaf(['M4-9-1'], PERM.freezeQuality),
  ...leaf(['M4-9-2'], PERM.freezeFinance),
  ...leaf(['M4-9-3'], PERM.unfreezeApprove),
  ...leaf(['M4-9-4'], PERM.warehouse),
  // M4-10 效期管理 4.10.1 效期预警 / 4.10.2 临期锁定（仓库） / 4.10.3 质量评估（质量链）
  ...leaf(['M4-10-1', 'M4-10-2'], PERM.warehouse),
  ...leaf(['M4-10-3'], PERM.expiryEval),
  // M4-11 盘点管理 4.11.1~4.11.3（change add-count-management：单一仓库主管域同 PERM）
  ...leaf(['M4-11-1', 'M4-11-2', 'M4-11-3'], PERM.warehouse),
  // M4-13 追溯召回 4.13.1/4.13.2（change add-trace-recall：四角色联合可见，服务层动作级分权）
  ...leaf(['M4-13-1', 'M4-13-2'], 'ROLE_QUALITY_ENG,ROLE_QUALITY_MGR,ROLE_WAREHOUSE,ROLE_ADMIN'),
  // M4-14 库存报表（change add-inventory-reports：周转为财务口径指标多两财务角色）
  ...leaf(['M4-14-1', 'M4-14-3'], 'ROLE_WAREHOUSE,ROLE_ADMIN'),
  ...leaf(['M4-14-2'], 'ROLE_WAREHOUSE,ROLE_ADMIN,ROLE_FINANCE,ROLE_FINANCE_MGR'),
  // M5-1 物料清单 5.1.1~5.1.4（change add-bom-management；父节点与子节点同权限防孤儿，M6 先例）
  ...leaf(['M5-1', 'M5-1-1', 'M5-1-2', 'M5-1-3', 'M5-1-4'], PERM.process),
  // M5-2 工艺管理 5.2.1~5.2.4（change add-routing-management；5.2.4 路线装配为本期新增叶子）
  ...leaf(['M5-2', 'M5-2-1', 'M5-2-2', 'M5-2-3', 'M5-2-4'], PERM.process),
  // M5-3 需求计划 5.3.1~5.3.3（change add-mrp-demand-planning：计划员+管理员，ROLE_PLANNER 新增）
  ...leaf(['M5-3', 'M5-3-1', 'M5-3-2', 'M5-3-3'], 'ROLE_PLANNER,ROLE_ADMIN'),
  // M5-4 工单管理 5.4.1~5.4.5（change add-work-order-management；父节点同权限防孤儿）
  ...leaf(['M5-4', 'M5-4-1', 'M5-4-2', 'M5-4-3', 'M5-4-4', 'M5-4-5'], PERM.planner),
  // M5-5 齐套检查 5.5.1~5.5.2（change add-kitting-check；父节点同权限防孤儿）
  ...leaf(['M5-5', 'M5-5-1', 'M5-5-2'], PERM.kitting),
  // M5-6 派工管理 5.6.1~5.6.3（change add-dispatch-management；父节点取四角色超集 ⊇ M5-6-3 防孤儿）
  ...leaf(['M5-6', 'M5-6-1', 'M5-6-2'], PERM.dispatch),
  ...leaf(['M5-6-3'], PERM.outsource),
  // M5-7 报工管理（change add-reporting-management，偏差 D1：蓝图 4 叶改 2 叶）
  // 5.7.1 报工录入=车间操作工；5.7.2 报工管理=计划两方（冲销/完工/预警）；父节点取两子并集防孤儿
  ...leaf(['M5-7'], 'ROLE_SHOP_FLOOR,ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_ADMIN'),
  ...leaf(['M5-7-1'], PERM.reportEntry),
  ...leaf(['M5-7-2'], PERM.reportAdmin),
  // M5-8 返工返修 5.8.1/5.8.2（change add-rework-management）：按使用者切两半
  // 5.8.1 返工工单=计划侧；5.8.2 返修处理=质量侧（计划员只读）；父节点 M5-8 取两子并集防孤儿
  ...leaf(['M5-8'], 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_ADMIN,ROLE_QUALITY_ENG,ROLE_QUALITY_MGR'),
  ...leaf(['M5-8-1'], PERM.reworkEntry),
  ...leaf(['M5-8-2'], PERM.reworkAdmin),
  // M5-9 试产管理 5.9.1~5.9.3（change add-trial-production-management）：三叶同一阶段视图按 TRIAL_STAGE 过滤
  // 权限相同（计划两方+工艺两方+PM+管理员）；父节点 M5-9 与子同权防孤儿
  ...leaf(['M5-9', 'M5-9-1', 'M5-9-2', 'M5-9-3'], PERM.trial),
  // M5-10 外协加工 5.10.1~5.10.4（change add-outsourcing-processing）：权限两条线
  // 5.10.1/3/4=计划采购（订单/发料/对账），5.10.2=质量（专检）；父节点取子超集防孤儿
  ...leaf(['M5-10'], 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_PURCHASER,ROLE_ADMIN,ROLE_INSPECTOR,ROLE_QUALITY_ENG,ROLE_QUALITY_MGR'),
  ...leaf(['M5-10-1', 'M5-10-3', 'M5-10-4'], 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_PURCHASER,ROLE_ADMIN'),
  ...leaf(['M5-10-2'], 'ROLE_INSPECTOR,ROLE_QUALITY_ENG,ROLE_QUALITY_MGR,ROLE_ADMIN'),
  // M5-11 成本归集 5.11.1~5.11.4（change add-cost-aggregation）：生产成本会计全权 + 计划线只读
  // 5.11.4 完工凭证加财务只读（spec completion-cost-voucher）；父节点 M5-11 取子超集防孤儿
  ...leaf(['M5-11'], 'ROLE_COST_ACCOUNTANT,ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_FINANCE,ROLE_FINANCE_MGR,ROLE_ADMIN'),
  ...leaf(['M5-11-1', 'M5-11-2', 'M5-11-3'], 'ROLE_COST_ACCOUNTANT,ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_ADMIN'),
  ...leaf(['M5-11-4'], 'ROLE_COST_ACCOUNTANT,ROLE_FINANCE,ROLE_FINANCE_MGR,ROLE_ADMIN'),
  // 16.11 工作日历 16.11.1~16.11.2（change add-dispatch-management；16 域默认 ADMIN，此映射加计划员只读）
  // 父节点 M16 取并集（ADMIN + PLANNER 只读），否则计划员视图下 16.11 被提升为顶级孤儿（M3 域先例）
  ...leaf(['M16'], 'ROLE_ADMIN,ROLE_PLANNER'),
  ...leaf(['M16-11', 'M16-11-1', 'M16-11-2'], PERM.calendar),
  // M7 资产管理 7.1.1~7.1.5（change add-asset-capitalization：资产管理员+财务资产会计）
  // 父节点 M7/M7-1 与子同权防孤儿（M3/M16 域先例）
  ...leaf(['M7', 'M7-1', 'M7-1-1', 'M7-1-2', 'M7-1-3', 'M7-1-4', 'M7-1-5'], PERM.eam),
  // M7-2 折旧管理 7.2.1~7.2.3（change add-asset-depreciation：月度计提+变动处理+凭证生成）
  // 父节点 M7-2 与子同权防孤儿；角色同 eam（资产管理员发起变更/财务资产会计执行计提审核）
  ...leaf(['M7-2', 'M7-2-1', 'M7-2-2', 'M7-2-3'], PERM.eam),
  // M7-3 资产运维 7.3.1~7.3.3（change add-asset-maintenance：设备工程师+设备主管）
  // M7 父节点取并集（含财务资产会计）后 spread 覆盖，防工程师看不到域节点
  ...leaf(['M7-3', 'M7-3-1', 'M7-3-2', 'M7-3-3'], PERM.maint),
  // M7-4 调拨租赁 7.4.1~7.4.3（change add-asset-transfer-lease：资产/财务两方 + 部门/财务经理）
  // 父节点 M7 取并集防孤儿
  ...leaf(['M7-4', 'M7-4-1', 'M7-4-2', 'M7-4-3'], 'ROLE_ASSET_ADMIN,ROLE_FIN_ASSET,ROLE_DEPT_HEAD,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M7-5 资产盘点 7.5.1~7.5.2（change add-asset-count：资产/财务/部门/财务经理）
  ...leaf(['M7-5', 'M7-5-1', 'M7-5-2'], 'ROLE_ASSET_ADMIN,ROLE_FIN_ASSET,ROLE_DEPT_HEAD,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M7-6 报废处置 7.6.1~7.6.3（change add-asset-disposal：资产/财务/部门/财务经理/总经理）
  ...leaf(['M7-6', 'M7-6-1', 'M7-6-2', 'M7-6-3'], 'ROLE_ASSET_ADMIN,ROLE_FIN_ASSET,ROLE_DEPT_HEAD,ROLE_FIN_MGR,ROLE_GM,ROLE_ADMIN'),
  // M7-7 技改大修 7.7.1（change add-asset-rework：资产/财务/部门/财务经理）
  ...leaf(['M7-7', 'M7-7-1'], 'ROLE_ASSET_ADMIN,ROLE_FIN_ASSET,ROLE_DEPT_HEAD,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M7-8 资产报表 7.8.1~7.8.3（change add-asset-report：资产/财务/部门/财务经理）
  ...leaf(['M7-8', 'M7-8-1', 'M7-8-2', 'M7-8-3'], 'ROLE_ASSET_ADMIN,ROLE_FIN_ASSET,ROLE_DEPT_HEAD,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M8-1 总账管理 8.1.1~8.1.5（change add-gl-management：财务资产/财务经理/管理员）
  ...leaf(['M8-1', 'M8-1-1', 'M8-1-2', 'M8-1-3', 'M8-1-4', 'M8-1-5'], 'ROLE_FIN_ASSET,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M8-2 应收管理 8.2.1~8.2.3（change add-ar-management：与 M8-1 对齐）
  ...leaf(['M8-2', 'M8-2-1', 'M8-2-2', 'M8-2-3'], 'ROLE_FIN_ASSET,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M8-3 应付管理 8.3.1~8.3.3（change add-ap-management：与 M8-1/M8-2 对齐）
  ...leaf(['M8-3', 'M8-3-1', 'M8-3-2', 'M8-3-3'], 'ROLE_FIN_ASSET,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M8-4 收付管理 8.4.1~8.4.3（change add-payment-collection：与 M8-1~3 对齐）
  ...leaf(['M8-4', 'M8-4-1', 'M8-4-2', 'M8-4-3'], 'ROLE_FIN_ASSET,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M8-5 外币核算 8.5.1~8.5.3（change add-fx-revaluation：与 M8-1~4 对齐）
  ...leaf(['M8-5', 'M8-5-1', 'M8-5-2', 'M8-5-3'], 'ROLE_FIN_ASSET,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M8-6 资产折旧 8.6.1~8.6.2（change add-depreciation-monitoring：财务只读监控，与 M8-1~5 对齐）
  ...leaf(['M8-6', 'M8-6-1', 'M8-6-2'], 'ROLE_FIN_ASSET,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M8-7 银行对账 8.7.1~8.7.4（change add-bank-reconciliation：与 M8-1~6 对齐）
  ...leaf(['M8-7', 'M8-7-1', 'M8-7-2', 'M8-7-3', 'M8-7-4'], 'ROLE_FIN_ASSET,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M8-8 费用报销 8.8.1~8.8.3（change add-expense-reimbursement：与 M8-1~7 对齐）
  ...leaf(['M8-8', 'M8-8-1', 'M8-8-2', 'M8-8-3'], 'ROLE_FIN_ASSET,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M8-9 预提待摊 8.9.1~8.9.2（change add-expense-reimbursement：与 M8-1~8 对齐）
  ...leaf(['M8-9', 'M8-9-1', 'M8-9-2'], 'ROLE_FIN_ASSET,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M8-10 成本核算 8.10.1~8.10.3（change add-cost-accounting：M8 惯例 + 成本会计 + spec 场景财务专员/主管）
  ...leaf(['M8-10', 'M8-10-1', 'M8-10-2', 'M8-10-3'], 'ROLE_COST_ACCOUNTANT,ROLE_FINANCE,ROLE_FINANCE_MGR,ROLE_FIN_ASSET,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M9-1 计税管理 9.1.1~9.1.3（change add-tax-calculation：ROLE_FINANCE 承担税务专员操作位）
  ...leaf(['M9-1', 'M9-1-1', 'M9-1-2', 'M9-1-3'], 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M9-2 发票管理 9.2.1~9.2.4（change add-invoice-management：对齐 M9-1 三角色）
  ...leaf(['M9-2', 'M9-2-1', 'M9-2-2', 'M9-2-3', 'M9-2-4'], 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M9-3 进项认证 9.3.1~9.3.2（change add-input-certification：接线 9.2.2 页，对齐三角色）
  ...leaf(['M9-3', 'M9-3-1', 'M9-3-2'], 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M9-4 红冲作废 9.4.1~9.4.3（change add-red-invoice-void：红字冲销/发票作废/进项转出三叶三页）
  ...leaf(['M9-4', 'M9-4-1', 'M9-4-2', 'M9-4-3'], 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M9-5 税务申报 9.5.1~9.5.4（change add-tax-filing：增值申报/所得申报/出口退税/申报归档四叶四页）
  ...leaf(['M9-5', 'M9-5-1', 'M9-5-2', 'M9-5-3', 'M9-5-4'], 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M9-6 税务风控 9.6.1~9.6.3（change add-tax-risk-control：风险预警/优惠管理/关联定价三叶三页）
  ...leaf(['M9-6', 'M9-6-1', 'M9-6-2', 'M9-6-3'], 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M10-1 员工档案 + M10-7 入转调离（change add-employee-archive：建档/维护/入职/转正/调岗/离职）
  ...leaf(['M10-1', 'M10-1-1', 'M10-1-2', 'M10-7', 'M10-7-1', 'M10-7-2', 'M10-7-3', 'M10-7-4'],
    'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN'),
  // M10-2 组织管理 10.2.1~10.2.4（change add-org-management：部门/岗位/汇报/职级四叶四页）
  ...leaf(['M10-2', 'M10-2-1', 'M10-2-2', 'M10-2-3', 'M10-2-4'], 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN'),
  // M10-3 考勤管理 10.3.1~10.3.5 + M10-4 加班调休 10.4.1~10.4.4（change add-attendance-management）
  ...leaf(['M10-3', 'M10-3-1', 'M10-3-2', 'M10-3-3', 'M10-3-4', 'M10-3-5',
    'M10-4', 'M10-4-1', 'M10-4-2', 'M10-4-3', 'M10-4-4'], 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN'),
  // M10-5 薪酬核算 10.5.1~10.5.4 + M10-6 薪酬发放 10.6.1~10.6.3（change add-payroll-management）
  ...leaf(['M10-5', 'M10-5-1', 'M10-5-2', 'M10-5-3', 'M10-5-4'],
    'ROLE_HR,ROLE_HR_MGR,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN'),
  ...leaf(['M10-6', 'M10-6-1', 'M10-6-2', 'M10-6-3'],
    'ROLE_HR,ROLE_HR_MGR,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M10-8 外派借调 10.8.1/10.8.2（change add-employee-secondment）
  ...leaf(['M10-8', 'M10-8-1', 'M10-8-2'], 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN'),
  // M10-9 培训认证 10.9.1~10.9.3（change add-training-certification：HR 全权 + 财务只读费用）
  ...leaf(['M10-9', 'M10-9-1', 'M10-9-2', 'M10-9-3'],
    'ROLE_HR,ROLE_HR_MGR,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M11-2 报价管理 11.2.1/11.2.2（change add-quote-approval-console：11.2.1 同 3.2 三角色，11.2.2 审批节点角色）
  ...leaf(['M11-2', 'M11-2-1'], 'ROLE_SALES,ROLE_SALES_MGR,ROLE_ADMIN'),
  ...leaf(['M11-2-2'], 'ROLE_SALES_MGR,ROLE_SALES_DIRECTOR,ROLE_FINANCE_MGR,ROLE_ADMIN'),
  // M11-3 项目立项 11.3.1~11.3.3（change add-project-initiation：PMO/PM，预算叶加财务）
  ...leaf(['M11-3', 'M11-3-1', 'M11-3-2'], 'ROLE_PMO,ROLE_PM,ROLE_ADMIN'),
  ...leaf(['M11-3-3'], 'ROLE_PMO,ROLE_PM,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M11-4 进度管理 11.4.1~11.4.3（change add-milestone-management：PM/PMO，进度叶加财务只读）
  ...leaf(['M11-4', 'M11-4-1', 'M11-4-2'], 'ROLE_PM,ROLE_PMO,ROLE_ADMIN'),
  ...leaf(['M11-4-3'], 'ROLE_PM,ROLE_PMO,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M11-5 工时管理 11.5.1~11.5.4（change add-timesheet-management：审核叶+DEPT_HEAD，归集/偏差叶+财务只读）
  ...leaf(['M11-5', 'M11-5-1'], 'ROLE_PM,ROLE_PMO,ROLE_ADMIN'),
  ...leaf(['M11-5-2'], 'ROLE_PM,ROLE_PMO,ROLE_DEPT_HEAD,ROLE_ADMIN'),
  ...leaf(['M11-5-3', 'M11-5-4'], 'ROLE_PM,ROLE_PMO,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M11-6 预算管控 11.6.1~11.6.3（change add-cost-collection-freeze：归集/分析含财务线，冻结叶不含普通 PM）
  ...leaf(['M11-6', 'M11-6-1', 'M11-6-2'], 'ROLE_PM,ROLE_PMO,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN'),
  ...leaf(['M11-6-3'], 'ROLE_PMO,ROLE_FINANCE,ROLE_EXEC,ROLE_ADMIN'),
  // M11-7 项目变更 11.7.1/11.7.2（change add-project-change：申请叶 PM，审批叶 PMO/财务/分管领导）
  ...leaf(['M11-7', 'M11-7-1'], 'ROLE_PM,ROLE_PMO,ROLE_ADMIN'),
  ...leaf(['M11-7-2'], 'ROLE_PMO,ROLE_FINANCE,ROLE_EXEC,ROLE_ADMIN'),
  // M11-8 知识产权 11.8.1~11.8.4（change add-ip-management：四叶差异化，仲裁叶仅法务线）
  ...leaf(['M11-8', 'M11-8-1'], 'ROLE_PM,ROLE_RD_LEAD,ROLE_PMO,ROLE_ADMIN'),
  ...leaf(['M11-8-2'], 'ROLE_RD_LEAD,ROLE_LEGAL,ROLE_PMO,ROLE_ADMIN'),
  ...leaf(['M11-8-3'], 'ROLE_LEGAL,ROLE_PMO,ROLE_ADMIN'),
  ...leaf(['M11-8-4'], 'ROLE_RD_LEAD,ROLE_LEGAL,ROLE_PMO,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M11-9 研发物料 11.9.1/11.9.2（change add-rnd-material：领用叶 PM/RD_LEAD，成本叶加财务线）
  ...leaf(['M11-9', 'M11-9-1'], 'ROLE_PM,ROLE_RD_LEAD,ROLE_PMO,ROLE_ADMIN'),
  ...leaf(['M11-9-2'], 'ROLE_PM,ROLE_RD_LEAD,ROLE_PMO,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN'),
  // M11-10 服务工单 11.10.1/11.10.2（change add-service-order：受理叶 CS/主管/销售经理，闭环叶加服务工程师）
  ...leaf(['M11-10', 'M11-10-1'], 'ROLE_CS,ROLE_SERVICE_MGR,ROLE_SALES_MGR,ROLE_ADMIN'),
  ...leaf(['M11-10-2'], 'ROLE_CS,ROLE_SERVICE_ENG,ROLE_SERVICE_MGR,ROLE_SALES_MGR,ROLE_ADMIN'),
  // M11-12 结项归档 11.12.1~4（change add-project-closure：检查/文档=PMO,PM；结算加财务线；释放仅 PMO）
  ...leaf(['M11-12', 'M11-12-1', 'M11-12-3'], 'ROLE_PMO,ROLE_PM,ROLE_ADMIN'),
  ...leaf(['M11-12-2'], 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_PMO,ROLE_ADMIN'),
  ...leaf(['M11-12-4'], 'ROLE_PMO,ROLE_ADMIN'),
  // M10-10 绩效管理 10.10.1~10.10.4（change add-payroll-management）
  ...leaf(['M10-10', 'M10-10-1', 'M10-10-2', 'M10-10-3', 'M10-10-4'],
    'ROLE_HR,ROLE_HR_MGR,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN'),
  ...leaf(['M7'], 'ROLE_EQUIP_ENG,ROLE_EQUIP_MGR,ROLE_ASSET_ADMIN,ROLE_FIN_ASSET,ROLE_DEPT_HEAD,ROLE_FIN_MGR,ROLE_ADMIN')
}
// add-sales-lead-to-cash design D2/D15：商机操作集中到 3.1，11.1.3/11.1.4 隐藏
const HIDDEN = new Set(['M11-1-3', 'M11-1-4'])
const rows = []
const pushRow = (n) => {
  const path = n.path !== undefined ? n.path : n.level === 3 ? `/m/${n.code}` : ''
  const icon = n.icon !== undefined ? n.icon : n.level === 1 ? DOMAIN_ICON[Number(n.code)] || '' : ''
  const isAdminOnly = Number(String(n.code).split('.')[0]) === ADMIN_DOMAIN || n.id === 'M16-10'
  // add-interface-integration：2.8.3 接口对接仅接口运维与管理员可见（与迁移 050 回填口径一致）
  const perm = EXTRA_PERM[n.id] || (isAdminOnly ? 'ROLE_ADMIN' : '')
  const status = HIDDEN.has(n.id) ? '0' : '1'
  rows.push([n.id, n.parentId, n.code, n.title, path, icon, n.sort, perm, status])
}
pushRow(extra.find((e) => e.id === 'M-HOME'))
for (const n of nodes) pushRow(n)
pushRow(extra.find((e) => e.id === 'M16-10'))

const header = `-- 099 种子数据：全量层次化菜单（自 docs/design/08-erp-menu.md 解析生成）
-- 生成命令：node backend/scripts/generate-menu-seed.js（勿手工编辑）
-- 一级 18 域 + 工作台；二级/三级为 08 文档清单；菜单管理（16.10）为工程新增导航入口
-- 路径约定：目录节点 PATH 为空；三级叶子 PATH=/m/<编号>（前端未实现页面由占位路由承接）
-- PERM：系统管理域（16）整体 ROLE_ADMIN；销售域按 add-sales-lead-to-cash design D15 分层；其余为空（所有登录用户可见）
-- HIDDEN：M11-1-3 / M11-1-4（已归入 3.1 商机管理，design D2）写 STATUS=0
-- 统计：一级 ${l1.length}，二级 ${l2.length}，三级 ${l3.length}，新增 2，隐藏 ${HIDDEN.size}，合计 ${rows.length}

INSERT IGNORE INTO erp_admin_menu (ID, PARENT_ID, MENU_CODE, TITLE, PATH, ICON, SORT_ORDER, PERM, STATUS) VALUES
`

const body = rows
  .map((r) => `(${r.map((v) => `'${esc(v)}'`).join(', ')})`)
  .join(',\n')

fs.writeFileSync(OUT, header + body + ';\n', 'utf8')
console.log(`OK ${OUT}`)
console.log(`一级 ${l1.length} / 二级 ${l2.length} / 三级 ${l3.length} / 新增 2 / 合计 ${rows.length}`)
