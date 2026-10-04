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
const rows = []
const pushRow = (n) => {
  const path = n.path !== undefined ? n.path : n.level === 3 ? `/m/${n.code}` : ''
  const icon = n.icon !== undefined ? n.icon : n.level === 1 ? DOMAIN_ICON[Number(n.code)] || '' : ''
  const isAdminOnly = Number(String(n.code).split('.')[0]) === ADMIN_DOMAIN || n.id === 'M16-10'
  const perm = isAdminOnly ? 'ROLE_ADMIN' : ''
  rows.push([n.id, n.parentId, n.code, n.title, path, icon, n.sort, perm, '1'])
}
pushRow(extra.find((e) => e.id === 'M-HOME'))
for (const n of nodes) pushRow(n)
pushRow(extra.find((e) => e.id === 'M16-10'))

const header = `-- 099 种子数据：全量层次化菜单（自 docs/design/08-erp-menu.md 解析生成）
-- 生成命令：node backend/scripts/generate-menu-seed.js（勿手工编辑）
-- 一级 18 域 + 工作台；二级/三级为 08 文档清单；菜单管理（16.10）为工程新增导航入口
-- 路径约定：目录节点 PATH 为空；三级叶子 PATH=/m/<编号>（前端未实现页面由占位路由承接）
-- PERM：系统管理域（16）整体 ROLE_ADMIN；其余为空（所有登录用户可见）
-- 统计：一级 ${l1.length}，二级 ${l2.length}，三级 ${l3.length}，新增 2，合计 ${rows.length}

INSERT IGNORE INTO erp_admin_menu (ID, PARENT_ID, MENU_CODE, TITLE, PATH, ICON, SORT_ORDER, PERM, STATUS) VALUES
`

const body = rows
  .map((r) => `(${r.map((v) => `'${esc(v)}'`).join(', ')})`)
  .join(',\n')

fs.writeFileSync(OUT, header + body + ';\n', 'utf8')
console.log(`OK ${OUT}`)
console.log(`一级 ${l1.length} / 二级 ${l2.length} / 三级 ${l3.length} / 新增 2 / 合计 ${rows.length}`)
