import request from '@/utils/request'

// ---------- 报价（3.2.1 创建 / 3.2.2 毛利 / 3.2.3 转化） ----------

export function getQuotesApi(params) {
  return request.get('/sd/quotes', { params })
}

export function getQuoteApi(id) {
  return request.get(`/sd/quotes/${id}`)
}

export function getQuoteLinesApi(id) {
  return request.get(`/sd/quotes/${id}/lines`)
}

export function getQuoteVersionsApi(id) {
  return request.get(`/sd/quotes/${id}/versions`)
}

/** 预检试算（不落库）：errors(L1)/warnings(L4) + 取价明细 + 毛利 */
export function precheckQuoteApi(body) {
  return request.post('/sd/quotes/precheck', body)
}

export function createQuoteApi(body) {
  return request.post('/sd/quotes', body)
}

export function updateQuoteApi(id, body) {
  return request.put(`/sd/quotes/${id}`, body)
}

/** 低毛利二次确认（0 ≤ m < MIN_MARGIN_RATE 提交前置） */
export function marginConfirmApi(id) {
  return request.post(`/sd/quotes/${id}/margin-confirm`)
}

/** 提交审批：小额高毛利自动发布；负毛利销售主管+财务会签 */
export function submitQuoteApi(id) {
  return request.post(`/sd/quotes/${id}/submit`)
}

/** 开新版本（旧版本 SUPERSEDED 保留可查） */
export function reviseQuoteApi(id) {
  return request.post(`/sd/quotes/${id}/revise`)
}

export function convertCheckApi(id) {
  return request.post(`/sd/quotes/${id}/convert-check`)
}

/** 转化 SO：生成订单回写报价，商机自动推进「合同签订」 */
export function convertQuoteApi(id) {
  return request.post(`/sd/quotes/${id}/convert`)
}
