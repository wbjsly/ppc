import request from '@/utils/request'

/** 分页（keyword/status；查询前服务端执行证照到期懒巡检） */
export function getSupplierPageApi(params) {
  return request.get('/mdm/suppliers', { params })
}

export function getSupplierApi(id) {
  return request.get(`/mdm/suppliers/${id}`)
}

/** 建档（黑名单 HIT 硬阻断 / 查重 409 → forceCreate 放行） */
export function createSupplierApi(data, forceCreate = false) {
  return request.post('/mdm/suppliers', data, { params: { forceCreate } })
}

export function updateSupplierApi(data) {
  return request.put('/mdm/suppliers', data)
}

/** 单步审核：APPROVED 通过 / REJECTED 驳回留痕 */
export function reviewSupplierApi(id, result, reason) {
  return request.put(`/mdm/suppliers/${id}/review`, null, { params: { result, reason } })
}

/** 状态迁移（五态矩阵守卫） */
export function changeSupplierStatusApi(id, toStatus, reason) {
  return request.put(`/mdm/suppliers/${id}/status`, null, { params: { toStatus, reason } })
}

/** 证照核验解除（CERT_EXPIRED → QUALIFIED） */
export function verifyCertsApi(id, reason) {
  return request.put(`/mdm/suppliers/${id}/certs-verify`, null, { params: { reason } })
}

export function getCertsApi(supplierId) {
  return request.get(`/mdm/suppliers/${supplierId}/certs`)
}

export function saveCertApi(supplierId, data) {
  return request.post(`/mdm/suppliers/${supplierId}/certs`, data)
}

export function updateCertApi(certId, data) {
  return request.put(`/mdm/suppliers/certs/${certId}`, data)
}

export function deleteCertApi(certId) {
  return request.delete(`/mdm/suppliers/certs/${certId}`)
}

/** 影响分析（最早到期证照 + 临期 + PO 桩） */
export function getImpactApi(id) {
  return request.get(`/mdm/suppliers/${id}/impact`)
}

/** 合格供应商下拉（仅 QUALIFIED） */
export function getSupplierOptionsApi() {
  return request.get('/mdm/suppliers/options')
}

export function getSupplierVersionsApi(id) {
  return request.get(`/mdm/suppliers/${id}/versions`)
}

export function getSupplierDiffApi(id, from, to) {
  return request.get(`/mdm/suppliers/${id}/diff`, { params: { from, to } })
}
