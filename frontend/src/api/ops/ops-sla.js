import request from '@/utils/request'

// ========== 18.1 变更管理 ==========

export function submitChangeApi(body) {
  return request({ url: '/ops/change-tickets', method: 'post', data: body })
}
export function approveChangeApi(id) {
  return request({ url: `/ops/change-tickets/${id}/approve`, method: 'post' })
}
export function startChangeApi(id) {
  return request({ url: `/ops/change-tickets/${id}/start`, method: 'post' })
}
export function rollbackChangeApi(id, reason) {
  return request({ url: `/ops/change-tickets/${id}/rollback`, method: 'post', params: { reason } })
}
export function closeChangeApi(id, rollbackReason) {
  return request({ url: `/ops/change-tickets/${id}/close`, method: 'post', params: { rollbackReason } })
}
export function listChangeApi(params) {
  return request({ url: '/ops/change-tickets', method: 'get', params })
}

// ========== 18.2 服务监控 ==========

export function listAlertsApi(params) {
  return request({ url: '/ops/alerts', method: 'get', params })
}
export function claimAlertApi(id, handler) {
  return request({ url: `/ops/alerts/${id}/claim`, method: 'post', params: { handler } })
}
export function resolveAlertApi(id, note) {
  return request({ url: `/ops/alerts/${id}/resolve`, method: 'post', params: { note } })
}
export function stormRootsApi(limit = 20) {
  return request({ url: '/ops/alerts/storm', method: 'get', params: { limit } })
}
export function collectDelayApi() {
  return request({ url: '/ops/alerts/sync-delay/collect', method: 'post' })
}
export function delayTrendApi(days = 14) {
  return request({ url: '/ops/alerts/sync-delay/trend', method: 'get', params: { days } })
}

// ========== 18.3 升级管理 ==========

export function oncallApi(days = 7) {
  return request({ url: '/ops/escalations/oncall', method: 'get', params: { days } })
}
export function saveOncallApi(dutyDate, level, userName, backupUser) {
  return request({
    url: '/ops/escalations/oncall/save', method: 'post',
    params: { dutyDate, level, userName, backupUser }
  })
}
export function escalationLogsApi(params) {
  return request({ url: '/ops/escalations/logs', method: 'get', params })
}
export function scanEscalationApi() {
  return request({ url: '/ops/escalations/scan', method: 'post' })
}
export function escalationTicketsApi() {
  return request({ url: '/ops/escalations/tickets', method: 'get' })
}

// ========== 18.4 容量管理 ==========

export function capacityMetricsApi(days = 30) {
  return request({ url: '/ops/capacity/metrics', method: 'get', params: { days } })
}
export function collectCapacityApi() {
  return request({ url: '/ops/capacity/collect', method: 'post' })
}
export function guardStatusApi() {
  return request({ url: '/ops/capacity/accounting-guard', method: 'get' })
}
export function setGuardApi(enabled) {
  return request({ url: '/ops/capacity/accounting-guard', method: 'post', params: { enabled } })
}

// ========== 18.5 服务报告 ==========

export function generateReportApi(period) {
  return request({ url: `/ops/sla-reports/${period}/generate`, method: 'post' })
}
export function getReportApi(period) {
  return request({ url: `/ops/sla-reports/${period}`, method: 'get' })
}
export function archiveReportApi(period) {
  return request({ url: `/ops/sla-reports/${period}/archive`, method: 'post' })
}
export function publishReportApi(period, scope) {
  return request({ url: `/ops/sla-reports/${period}/publish`, method: 'post', params: { scope } })
}
export function improvementsApi(params) {
  return request({ url: '/ops/sla-reports/improvements', method: 'get', params })
}
