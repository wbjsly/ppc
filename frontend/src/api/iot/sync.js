import request from '@/utils/request'

// ---------- 13.5 离线同步 ----------
export function syncUploadApi(records) {
  return request.post('/iot/sync/upload', records)
}
export function syncConflictsApi(params) {
  return request.get('/iot/sync/conflicts', { params })
}
export function syncResolveApi(id, data) {
  return request.post(`/iot/sync/conflicts/${id}/resolve`, data)
}
export function syncLockStatusApi(deviceCode) {
  return request.get('/iot/sync/lock-status', { params: { deviceCode } })
}
export function syncQueueApi(params) {
  return request.get('/iot/sync/queue', { params })
}
export function syncCleanupApi(deviceCode) {
  return request.post('/iot/sync/cleanup', null, { params: { deviceCode } })
}
export function syncScanLocksApi() {
  return request.post('/iot/sync/scan-locks')
}
