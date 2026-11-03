/**
 * 工作台 widget 目录（add-configurable-workbench，spec personal-workbench
 * 「组件库注册与角色可见性」，design D4 注册制唯一注册点）。
 *
 * - 新增 widget：在此加一条 + 在 widgets/ 下建同名 file 组件（容器按 file 动态解析）。
 * - perm：本地角色预过滤标签（与菜单 meta.perm 同构），空数组 = 登录即可看；
 *   必须与后端端点权限一致（任务 3.4 逐条核对——perm 漏标会让无权用户发出 403 请求，
 *   累积触发 AUTHZ_BURST 降权，见 design Risks）。
 * - status: 'ready' 已实现可入画布；'planned' 灰显占位不可加入（spec 场景）。
 * - endpoint：数据端点元数据（供核对表与测试断言；实际请求在各 widget 组件内）。
 *
 * 权限核对表（2026-11-03，对照 SecurityConfig URL 规则实测）：
 *   todo/notice/shortcuts → /api/iot、/api/notices、menus 读 authenticated（perm: []）
 *   kpi 系列 → /api/bi/kpi/** authenticated（perm: []）
 *   ops-alert → /api/ops/** authenticated（perm: []）
 *   iot-alarm → /api/iot/** authenticated（perm: []）
 *   period → /api/fin/** GET hasAnyRole(ADMIN,PM)（perm: ['ROLE_ADMIN','ROLE_PM']——
 *     漏标会让无角色用户 403，且 403+登出是「单卡失败不传播」的反例）
 *   authz-alert → GET /api/system/authz-incidents URL 规则 hasRole(ADMIN)（perm: ['ROLE_ADMIN']，
 *     零角色实测 403；敏感四前缀由 AuthzDenyFilter 降权兕底）
 */
export const DASH_WIDGETS = [
  // ---- A 核心三件套 ----
  { id: 'todo', title: '我的待办', perm: [], w: 4, h: 4, file: 'todo-widget', status: 'ready', endpoint: '/api/iot/todos' },
  { id: 'notice', title: '站内通知', perm: [], w: 4, h: 4, file: 'notice-widget', status: 'ready', endpoint: '/api/notices' },
  { id: 'shortcuts', title: '常用快捷入口', perm: [], w: 4, h: 4, file: 'shortcuts-widget', status: 'ready', endpoint: '(local)' },
  // ---- B 指标看板包 ----
  { id: 'kpi-summary', title: 'KPI 摘要', perm: [], w: 4, h: 4, file: 'kpi-summary-widget', status: 'ready', endpoint: '/api/bi/kpi/summary' },
  { id: 'kpi-trend', title: 'KPI 趋势', perm: [], w: 8, h: 4, file: 'kpi-trend-widget', status: 'ready', endpoint: '/api/bi/kpi/trend' },
  { id: 'kpi-alerts', title: 'KPI 预警', perm: [], w: 4, h: 4, file: 'kpi-alerts-widget', status: 'ready', endpoint: '/api/bi/kpi/alerts' },
  { id: 'period', title: '会计期间', perm: ['ROLE_ADMIN', 'ROLE_PM'], w: 4, h: 4, file: 'period-widget', status: 'ready', endpoint: '/api/fin/periods/current' },
  // ---- C 告警监控包 ----
  { id: 'ops-alert', title: '运维告警', perm: [], w: 4, h: 4, file: 'ops-alert-widget', status: 'ready', endpoint: '/api/ops/alerts' },
  { id: 'iot-alarm', title: 'IoT 设备告警', perm: [], w: 6, h: 4, file: 'iot-alarm-widget', status: 'ready', endpoint: '/api/iot/alarms' },
  { id: 'authz-alert', title: '越权安全告警', perm: ['ROLE_ADMIN'], w: 6, h: 4, file: 'authz-alert-widget', status: 'ready', endpoint: '/api/system/authz-incidents' },
  // ---- 占位（注册制：实现后把 status 改 ready 即接入） ----
  { id: 'sales-funnel', title: '销售漏斗', perm: [], w: 6, h: 4, file: null, status: 'planned', endpoint: '(planned)' },
  { id: 'stock-alert', title: '库存预警', perm: [], w: 6, h: 4, file: null, status: 'planned', endpoint: '(planned)' },
]

/** 无 perm 标签或与当前角色有交集即可见（含 planned 灰显占位）。roles 为完整角色名（ROLE_ 前缀）。 */
export function visibleWidgets(roles) {
  const mine = new Set(roles || [])
  return DASH_WIDGETS.filter(w => !w.perm || w.perm.length === 0 || w.perm.some(r => mine.has(r)))
}

/** 可加入画布的组件 = 可见 且 已实现（planned 灰显不可拖入，spec 场景）。 */
export function addableWidgets(roles) {
  return visibleWidgets(roles).filter(w => w.status === 'ready')
}

/** 按 id 取目录项。 */
export function widgetById(id) {
  return DASH_WIDGETS.find(w => w.id === id) || null
}
