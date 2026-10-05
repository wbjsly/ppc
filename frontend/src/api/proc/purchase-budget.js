import request from '@/utils/request'

// 采购预算 API（design D7，spec purchase-budget）

/** 预算列表（年度/科目，含累计、剩余与 110% 阈值） */
export function getBudgetListApi(params) {
  return request.get('/proc/budgets', { params })
}

/** 使用视图（预算行 ∪ NO_BUDGET 行） */
export function getBudgetUsageApi(year) {
  return request.get('/proc/budgets/usage', { params: { year } })
}

/** 录入预算（同年度同科目唯一） */
export function createBudgetApi(payload) {
  return request.post('/proc/budgets', payload)
}

/** 修改预算（变更原因必填，留痕） */
export function updateBudgetApi(id, payload) {
  return request.put(`/proc/budgets/${id}`, payload)
}
