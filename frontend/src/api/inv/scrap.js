import request from '@/utils/request'

/** 创建报废单（按原因分流门槛；spec scrap-order） */
export function createScrapApi(data) {
  return request.post('/inv/scrap-orders', data)
}

export function updateScrapApi(id, data) {
  return request.put(`/inv/scrap-orders/${id}`, data)
}

export function cancelScrapApi(id, reason) {
  return request.post(`/inv/scrap-orders/${id}/cancel`, null, { params: { reason } })
}

/** 提交三方会签（仅 STALE：技术/质量/财务，C-4.4-14） */
export function submitScrapApprovalApi(id) {
  return request.post(`/inv/scrap-orders/${id}/submit-approval`)
}

/** 简化批准（QUALITY/DAMAGE/OTHER；STALE 422 须会签） */
export function approveScrapApi(id) {
  return request.post(`/inv/scrap-orders/${id}/approve`)
}

/** 出库过账（引擎 SCRAP_OUT + 凭证一） */
export function postScrapApi(id) {
  return request.post(`/inv/scrap-orders/${id}/post`)
}

/** 处置核销（凭证二 → DISPOSED，凭报废单号） */
export function disposeScrapApi(id) {
  return request.post(`/inv/scrap-orders/${id}/dispose`)
}

export function getScrapApi(id) {
  return request.get(`/inv/scrap-orders/${id}`)
}

/** 报废单列表 */
export function getScrapsApi(params) {
  return request.get('/inv/scrap-orders', { params })
}
