import request from '@/utils/request'

// ---------- 返利结算（spec sales-rebate，3.9.1~3.9.3） ----------

// ---- 三配置（3.9.1 页内 Tab，D13 不新增菜单） ----

export function getTargetsApi(params) {
  return request({ url: '/sd/rebate/targets', method: 'get', params })
}

export function saveTargetApi(data) {
  return request({ url: '/sd/rebate/targets', method: 'post', data })
}

export function deleteTargetApi(id) {
  return request({ url: `/sd/rebate/targets/${id}/delete`, method: 'post' })
}

export function getPoliciesApi(params) {
  return request({ url: '/sd/rebate/policies', method: 'get', params })
}

export function savePolicyApi(data) {
  return request({ url: '/sd/rebate/policies', method: 'post', data })
}

export function deletePolicyApi(id) {
  return request({ url: `/sd/rebate/policies/${id}/delete`, method: 'post' })
}

export function getBudgetApi(year) {
  return request({ url: '/sd/rebate/budgets', method: 'get', params: { year } })
}

export function saveBudgetApi(data) {
  return request({ url: '/sd/rebate/budgets', method: 'post', data })
}

export function getBudgetSummaryApi(year, quarter) {
  return request({ url: '/sd/rebate/budget-summary', method: 'get', params: { year, quarter } })
}

// ---- 计算 / 结算 ----

/** 季度返利计算（超额累进 + 预算校验，生成 DRAFT 结算单） */
export function calculateRebateApi(data) {
  return request({ url: '/sd/rebate/calculate', method: 'post', data })
}

export function getSettlementsApi(params) {
  return request({ url: '/sd/rebate/settlements', method: 'get', params })
}

export function getSettlementDetailApi(id) {
  return request({ url: `/sd/rebate/settlements/${id}`, method: 'get' })
}

/** 提交审批（超预算须附超预算说明与年度平衡方案，升级总监 C-4.3-05） */
export function submitSettlementApi(id, data) {
  return request({ url: `/sd/rebate/settlements/${id}/submit`, method: 'post', data })
}

/** 执行：OFFSET 应付冲抵 / CASH 现金兑现，生成结算凭证 */
export function executeSettlementApi(id, data) {
  return request({ url: `/sd/rebate/settlements/${id}/execute`, method: 'post', data })
}
