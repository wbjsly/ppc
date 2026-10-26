import request from '@/utils/request'

// ============ 预算基线与变更（add-project-initiation，11.3.3） ============

export function budgetLines(projectId) {
  return request({ url: '/api/pm/budgets', method: 'get', params: { projectId } })
}
export function budgetSaveLines(projectId, data) {
  return request({ url: `/api/pm/budgets/${projectId}/lines`, method: 'post', data })
}
export function budgetSubmit(projectId) {
  return request({ url: `/api/pm/budgets/${projectId}/submit`, method: 'post' })
}
export function budgetSummary(projectId) {
  return request({ url: `/api/pm/budgets/${projectId}/summary`, method: 'get' })
}

// ---------- 预算变更（BR-4.8-12） ----------

export function changeList(params) {
  return request({ url: '/api/pm/budget-changes', method: 'get', params })
}
export function changeSubmit(projectId, data) {
  return request({ url: `/api/pm/budget-changes/${projectId}`, method: 'post', data })
}

// ---------- 岗位费率 ----------

export function rateList() {
  return request({ url: '/api/pm/rates', method: 'get' })
}
export function rateSave(data) {
  return request({ url: '/api/pm/rates', method: 'post', data })
}
