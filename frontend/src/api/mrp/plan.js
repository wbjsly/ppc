import request from '@/utils/request'

/** 触发正式 MRP 运行（范围 FULL/CATEGORY/GROUP 三选一；单运行互斥） */
export function runMrpApi(body) {
  return request.post('/mrp/runs', body)
}

/** 运行历史（RUN_NO/范围/时间/统计） */
export function getRunsApi() {
  return request.get('/mrp/runs')
}

/** 建议列表（type/status/runId/keyword；exceptionsOnly 聚合 EXCESS+OVERDUE） */
export function getSuggestionsApi(params) {
  return request.get('/mrp/suggestions', { params })
}

/** 确认建议（可改量/日期，原值留痕） */
export function confirmSuggestionApi(id, data) {
  return request.post(`/mrp/suggestions/${id}/confirm`, data || {})
}

/** 取消建议（原因必填） */
export function cancelSuggestionApi(id, reason) {
  return request.post(`/mrp/suggestions/${id}/cancel`, { reason })
}

/** 批量生成请购单（复用自动请购链路） */
export function convertPrApi(ids) {
  return request.post('/mrp/suggestions/convert-pr', { ids })
}

/** 批量转计划工单（PMO 占位单号） */
export function convertMoApi(ids) {
  return request.post('/mrp/suggestions/convert-mo', { ids })
}

/** 异常标记已处理（备注必填） */
export function handleSuggestionApi(id, note) {
  return request.post(`/mrp/suggestions/${id}/handle`, { note })
}
