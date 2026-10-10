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
  planner: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_ADMIN'
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
  ...leaf(['M5-4', 'M5-4-1', 'M5-4-2', 'M5-4-3', 'M5-4-4', 'M5-4-5'], PERM.planner)
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
