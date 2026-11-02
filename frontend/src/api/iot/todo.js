import request from '@/utils/request'

// ---------- 13.7 移动待办/审批 ----------
export function todoAggregateApi(params) {
  return request.get('/iot/todos', { params })
}
export function todoCreateApi(data) {
  return request.post('/iot/todos', data)
}
export function todoHandleApi(id, data) {
  return request.post(`/iot/todos/${id}/handle`, data)
}
export function todoScanEscalateApi() {
  return request.post('/iot/todos/scan-escalate')
}
export function verifySignApi(data) {
  return request.post('/iot/terminals/verify-sign', data)
}

// ---------- 13.9 服务报告 ----------
export function slaGenerateApi(period) {
  return request.post('/iot/sla-reports/generate', null, { params: { period } })
}
export function slaArchiveApi(period) {
  return request.post(`/iot/sla-reports/${period}/archive`)
}
export function slaPublishApi(period) {
  return request.post(`/iot/sla-reports/${period}/publish`)
}
export function slaListApi() {
  return request.get('/iot/sla-reports')
}
export function slaSubscribeApi(subscriber) {
  return request.post('/iot/sla-reports/subscribe', { subscriber })
}
