import request from '@/utils/request'

// ---------- 工时管理（add-timesheet-management，M11.5；/my 填报 + 11.5.1~4） ----------

// /my 自助端
export function myToday() {
  return request({ url: '/api/my/timesheets/today', method: 'get' })
}

export function myList(params) {
  return request({ url: '/api/my/timesheets', method: 'get', params })
}

export function saveTimesheet(data) {
  return request({ url: '/api/my/timesheets', method: 'post', data })
}

export function submitTimesheet(id) {
  return request({ url: `/api/my/timesheets/${id}/submit`, method: 'post' })
}

// 审核（11.5.2）
export function pendingReview() {
  return request({ url: '/api/my/timesheets/pending-review', method: 'get' })
}

export function reviewTimesheet(id, data) {
  return request({ url: `/api/my/timesheets/${id}/review`, method: 'post', data })
}

export function bulkApprove(ids) {
  return request({ url: '/api/my/timesheets/bulk-approve', method: 'post', data: ids })
}

// 归集（11.5.3）
export function collectSummary(projectId) {
  return request({ url: '/api/pm/hours/collect/summary', method: 'get', params: { projectId } })
}

// 偏差（11.5.4）
export function varianceList(projectId) {
  return request({ url: '/api/pm/hours/variance', method: 'get', params: { projectId } })
}

export function handleAlert(id, note) {
  return request({ url: `/api/pm/hours/variance/alerts/${id}/handle`, method: 'post', params: { note } })
}

// 项目下拉
export function pmProjects() {
  return request({ url: '/api/pm/projects', method: 'get' })
}

// WBS 任务下拉（level=3，带负责人职级）
export function wbsTasks(projectId) {
  return request({ url: '/api/pm/wbs', method: 'get', params: { projectId } })
}
