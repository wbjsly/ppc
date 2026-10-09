import request from '@/utils/request'

// ---------- 4.7.1 拣货任务 ----------
export function getPickTasksApi(params) {
  return request.get('/inv/pick-tasks', { params })
}
export function getPickTaskDetailApi(id) {
  return request.get(`/inv/pick-tasks/${id}`)
}
export function assignPickTaskApi(id, picker) {
  return request.post(`/inv/pick-tasks/${id}/assign`, { picker })
}
export function cancelPickTaskApi(id, reason) {
  return request.post(`/inv/pick-tasks/${id}/cancel`, { reason })
}
export function transitionPickTaskApi(id, from, to) {
  return request.post(`/inv/pick-tasks/${id}/transition`, { from, to })
}

// ---------- 4.7.2 扫码确认 ----------
export function verifyScanApi(data) {
  return request.post('/inv/pick-scans/verify', data)
}
export function confirmPickLineApi(data) {
  return request.post('/inv/pick-scans/confirm-line', data)
}
export function registerShortApi(data) {
  return request.post('/inv/pick-scans/register-short', data)
}

// ---------- 4.7.3 出库复核 ----------
export function reviewPickLineApi(data) {
  return request.post('/inv/pick-reviews/review', data)
}
export function returnToPickApi(data) {
  return request.post('/inv/pick-reviews/return-to-pick', data)
}

// ---------- 4.7.4 差异处理 ----------
export function getPickDiffsApi(params) {
  return request.get('/inv/pick-diffs', { params })
}
export function closePickDiffApi(id, note) {
  return request.post(`/inv/pick-diffs/${id}/close`, { note })
}

// ---------- 冻结挂起恢复（spec freeze-management ADDED 需求①） ----------
export function resumePickTaskApi(id) {
  return request.post(`/inv/pick-tasks/${id}/resume`)
}
