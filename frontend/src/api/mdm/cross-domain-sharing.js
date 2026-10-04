import request from '@/utils/request'

// ---------- 事件流（7.2 Outbox 台账） ----------

export function getOutboxPageApi(params) {
  return request.get('/mdm/outbox', { params })
}

export function getOutboxDetailApi(id) {
  return request.get(`/mdm/outbox/${id}`)
}

// ---------- 价格协议 ----------

export function getAgreementPageApi(params) {
  return request.get('/mdm/price-agreements', { params })
}

export function getAgreementApi(id) {
  return request.get(`/mdm/price-agreements/${id}`)
}

export function createAgreementApi(data) {
  return request.post('/mdm/price-agreements', data)
}

export function updateAgreementApi(data) {
  return request.put('/mdm/price-agreements', data)
}

/** 停用（0/1→3）与恢复（3→按日期推算） */
export function stopAgreementApi(id, reason) {
  return request.put(`/mdm/price-agreements/${id}/stop`, null, { params: { reason } })
}

export function getAgreementVersionsApi(id) {
  return request.get(`/mdm/price-agreements/${id}/versions`)
}

export function getAgreementDiffApi(id, from, to) {
  return request.get(`/mdm/price-agreements/${id}/diff`, { params: { from, to } })
}

/** 试算（FR-4.3-4-3 优先级子集） */
export function trialApi(params) {
  return request.get('/mdm/price-agreements/trial', { params })
}
