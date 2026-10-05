import request from '@/utils/request'

/** 计量器具校准（6.9.1~6.9.3） */

export function getGaugePageApi(params) {
  return request.get('/qms/gauges', { params })
}
export function getGaugeDetailApi(id) {
  return request.get(`/qms/gauges/${id}`)
}
export function saveGaugeApi(data) {
  return request.post('/qms/gauges', data)
}
/** 校准执行（FAIL → 停用 + 可疑批次追溯） */
export function calibrateApi(gaugeId, data) {
  return request.post(`/qms/gauges/${gaugeId}/calibrations`, data)
}
export function warningsApi() {
  return request.get('/qms/gauges/warnings')
}
export function suspectsApi(gaugeCode) {
  return request.get('/qms/gauges/suspects', { params: { gaugeCode } })
}
export function evalSuspectApi(id, conclusion) {
  return request.post(`/qms/gauges/suspects/${id}/eval`, { conclusion })
}
export function traceApi(gaugeCode, fromDate) {
  return request.get('/qms/gauges/trace', { params: { gaugeCode, fromDate } })
}
