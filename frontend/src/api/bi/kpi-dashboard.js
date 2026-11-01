import request from '@/utils/request'

/**
 * 经营看板控制台（14.7，add-kpi-dashboard，spec bi-kpi-dashboard）。
 * 读=登录即可（数据范围行级注入）；定义/目标写+手动快照=BI_DESIGNER/ADMIN。
 */

// ---------------- 14.7.1 指标达成 ----------------

/** 达成概览（KPI 卡片三色） */
export function getSummaryApi() {
  return request.get('/bi/kpi/summary')
}

// ---------------- 14.7.2 趋势展示 ----------------

/** 趋势序列（近 N 日快照 + 目标线） */
export function getTrendApi(kpiKey, dimValue, days = 0) {
  return request.get('/bi/kpi/trend', { params: { kpiKey, dimValue, days } })
}

// ---------------- 14.7.3 异常预警 ----------------

/** 预警列表（ACTIVE/RECOVERED） */
export function getAlertsApi(status) {
  return request.get('/bi/kpi/alerts', { params: { status } })
}

// ---------------- KPI 配置 ----------------

export function getDefsApi() {
  return request.get('/bi/kpi/defs')
}

export function saveDefApi(row) {
  return request.post('/bi/kpi/defs', row)
}

export function updateDefApi(id, row) {
  return request.put(`/bi/kpi/defs/${id}`, row)
}

export function deleteDefApi(id) {
  return request.delete(`/bi/kpi/defs/${id}`)
}

export function getTargetsApi(kpiKey) {
  return request.get('/bi/kpi/targets', { params: { kpiKey } })
}

export function saveTargetApi(row) {
  return request.post('/bi/kpi/targets', row)
}

/** 手动触发快照（BI_DESIGNER/ADMIN） */
export function runSnapshotApi() {
  return request.post('/bi/kpi/snapshot/run')
}
