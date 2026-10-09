import request from '@/utils/request'

// ---------- 4.11.1 周期盘点（手工选范围） ----------
export function createCycleTaskApi(data) {
  return request.post('/inv/count/tasks/cycle', data)
}

// ---------- 4.11.2 全面盘点（一键全仓） ----------
export function createFullTaskApi(data) {
  return request.post('/inv/count/tasks/full', data)
}

// ---------- 任务通用 ----------
export function getCountTasksApi(params) {
  return request.get('/inv/count/tasks', { params })
}
export function getCountTaskDetailApi(id) {
  return request.get(`/inv/count/tasks/${id}`)
}
export function getCountLinesApi(id, params) {
  return request.get(`/inv/count/tasks/${id}/lines`, { params })
}
export function cancelCountTaskApi(id, reason) {
  return request.post(`/inv/count/tasks/${id}/cancel`, null, { params: { reason } })
}

// ---------- 录入与回显 ----------
export function submitCountApi(lineId, data) {
  return request.post(`/inv/count/lines/${lineId}/count`, data)
}
export function getCountResultApi(lineId) {
  return request.get(`/inv/count/lines/${lineId}/result`)
}

// ---------- 4.11.3 差异审批（台账侧；签署走 approvals） ----------
export function getCountDiffsApi(params) {
  return request.get('/inv/count/diffs', { params })
}
export function getCountWatchListApi() {
  return request.get('/inv/count/watch-list')
}
