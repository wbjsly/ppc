import request from '@/utils/request'

/** 分页（baseCcy/quoteCcy/rateType/lifecycle 计算态筛选） */
export function getRatePageApi(params) {
  return request.get('/mdm/exchange-rates', { params })
}

export function getRateApi(id) {
  return request.get(`/mdm/exchange-rates/${id}`)
}

export function createRateApi(data) {
  return request.post('/mdm/exchange-rates', data)
}

export function updateRateApi(data) {
  return request.put('/mdm/exchange-rates', data)
}

export function deleteRateApi(id) {
  return request.delete(`/mdm/exchange-rates/${id}`)
}

/** 试算（缺省类型 MIDDLE→BUY→SELL 回退；缺失明示 reasons） */
export function trialApi(params) {
  return request.get('/mdm/exchange-rates/trial', { params })
}

export function getRateVersionsApi(id) {
  return request.get(`/mdm/exchange-rates/${id}/versions`)
}

export function getRateDiffApi(id, from, to) {
  return request.get(`/mdm/exchange-rates/${id}/diff`, { params: { from, to } })
}

/** 区间历史链（1.5.3）：币对×类型全量升序，含已失效与计算态 */
export function getSequenceApi(params) {
  return request.get('/mdm/exchange-rates/sequence', { params })
}

/** 全局版本时间线（1.5.3）：跨记录快照倒序分页（含事件列） */
export function getHistoryApi(params) {
  return request.get('/mdm/exchange-rates/history', { params })
}
