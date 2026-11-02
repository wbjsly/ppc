import request from '@/utils/request'

// ---------- 13.2 扫码作业五场景（薄适配） ----------
export function scanReceiveApi(data) {
  return request.post('/iot/scan/receive', data)
}
export function scanPutawayApi(data) {
  return request.post('/iot/scan/putaway', data)
}
export function scanPickApi(data) {
  return request.post('/iot/scan/pick', data)
}
export function scanShipReviewApi(data) {
  return request.post('/iot/scan/ship-review', data)
}
export function scanCountApi(data) {
  return request.post('/iot/scan/count', data)
}
