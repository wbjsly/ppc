import request from '@/utils/request'

// ---------- 预算管控（add-cost-collection-freeze，M11.6） ----------

// ---- 归集台账（11.6.1 Tab1） ----
export function collectList(params) {
  return request({ url: '/api/pm/cost/collects', method: 'get', params })
}

export function collectSummary(projectId) {
  return request({ url: '/api/pm/cost/collects/summary', method: 'get', params: { projectId } })
}

// ---- 项目领料单（11.6.1 Tab2） ----
export function matIssueList(params) {
  return request({ url: '/api/pm/cost/material-issues', method: 'get', params })
}

export function matIssueDetail(id) {
  return request({ url: `/api/pm/cost/material-issues/${id}`, method: 'get' })
}

export function saveMatIssue(data) {
  return request({ url: '/api/pm/cost/material-issues', method: 'post', data })
}

export function submitMatIssue(data) {
  return request({ url: '/api/pm/cost/material-issues/submit', method: 'post', data })
}

export function reviewMatIssue(id, data) {
  return request({ url: `/api/pm/cost/material-issues/${id}/review`, method: 'post', data })
}

// ---- 外协合同与付款（11.6.1 Tab3） ----
export function subcontractContracts(params) {
  return request({ url: '/api/pm/cost/subcontracts/contracts', method: 'get', params })
}

export function saveSubcontract(data) {
  return request({ url: '/api/pm/cost/subcontracts/contracts', method: 'post', data })
}

export function subcontractPayments(params) {
  return request({ url: '/api/pm/cost/subcontracts/payments', method: 'get', params })
}

export function saveSubPayment(data) {
  return request({ url: '/api/pm/cost/subcontracts/payments', method: 'post', data })
}

export function submitSubPayment(data) {
  return request({ url: '/api/pm/cost/subcontracts/payments/submit', method: 'post', data })
}

export function reviewSubPayment(id, data) {
  return request({ url: `/api/pm/cost/subcontracts/payments/${id}/review`, method: 'post', data })
}

// ---- 差旅报销（11.6.1 Tab4） ----
export function travelStandards() {
  return request({ url: '/api/pm/cost/travels/standards', method: 'get' })
}

export function saveTravelStandard(data) {
  return request({ url: '/api/pm/cost/travels/standards', method: 'post', data })
}

export function travelClaims(params) {
  return request({ url: '/api/pm/cost/travels/claims', method: 'get', params })
}

export function saveTravelClaim(data) {
  return request({ url: '/api/pm/cost/travels/claims', method: 'post', data })
}

export function submitTravelClaim(data) {
  return request({ url: '/api/pm/cost/travels/claims/submit', method: 'post', data })
}

export function reviewTravelClaim(id, data) {
  return request({ url: `/api/pm/cost/travels/claims/${id}/review`, method: 'post', data })
}

// ---- 间接分摊（11.6.1 Tab5） ----
export function allocRules() {
  return request({ url: '/api/pm/cost/allocs/rules', method: 'get' })
}

export function saveAllocRule(data) {
  return request({ url: '/api/pm/cost/allocs/rules', method: 'post', data })
}

export function disableAllocRule(id) {
  return request({ url: `/api/pm/cost/allocs/rules/${id}/disable`, method: 'post' })
}

export function costPools(params) {
  return request({ url: '/api/pm/cost/allocs/pools', method: 'get', params })
}

export function saveCostPool(data) {
  return request({ url: '/api/pm/cost/allocs/pools', method: 'post', data })
}

export function allocDetails(period) {
  return request({ url: '/api/pm/cost/allocs/details', method: 'get', params: { period } })
}

export function runAlloc(period) {
  return request({ url: '/api/pm/cost/allocs/run', method: 'post', params: { period } })
}

// ---- 偏差监控（11.6.2） ----
export function costVariance(projectId) {
  return request({ url: `/api/pm/cost/variance/${projectId}`, method: 'get' })
}

export function costAlerts(params) {
  return request({ url: '/api/pm/cost/alerts', method: 'get', params })
}

export function handleCostAlert(id, data) {
  return request({ url: `/api/pm/cost/alerts/${id}/handle`, method: 'post', data })
}

// ---- 超支冻结与超预算审批（11.6.3） ----
export function frozenProjects() {
  return request({ url: '/api/pm/cost/over-budgets/frozen', method: 'get' })
}

export function overBudgetList(params) {
  return request({ url: '/api/pm/cost/over-budgets', method: 'get', params })
}

export function submitOverBudget(data) {
  return request({ url: '/api/pm/cost/over-budgets', method: 'post', data })
}

// ---- 项目选择器（页面共用） ----
export function pmProjects() {
  return request({ url: '/api/pm/projects', method: 'get' })
}
