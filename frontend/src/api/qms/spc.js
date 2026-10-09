import request from '@/utils/request'

/** SPC 过程控制（6.10.1~6.10.2） */

export function recordSampleApi(data) {
  return request.post('/qms/spc/samples', data)
}
export function samplesApi(charCode, limit = 100) {
  return request.get('/qms/spc/samples', { params: { charCode, limit } })
}
export function alertsApi(params) {
  return request.get('/qms/spc/alerts', { params })
}
export function handleAlertApi(id, result) {
  return request.post(`/qms/spc/alerts/${id}/handle`, { result })
}
export function trendsApi() {
  return request.get('/qms/spc/trends')
}
