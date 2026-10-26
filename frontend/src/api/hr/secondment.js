import request from '@/utils/request'

// ---------- 外派借调（secondment-management，10.8.1/10.8.2） ----------

export function secSubmit(data) {
  return request({ url: '/api/hr/secondments', method: 'post', data })
}
export function secList(params) {
  return request({ url: '/api/hr/secondments', method: 'get', params })
}
export function secDetail(id) {
  return request({ url: `/api/hr/secondments/${id}`, method: 'get' })
}
export function secConfirmOut(id, data) {
  return request({ url: `/api/hr/secondments/${id}/confirm-out`, method: 'post', data })
}
export function secConfirmIn(id, data) {
  return request({ url: `/api/hr/secondments/${id}/confirm-in`, method: 'post', data })
}
export function secHrReview(id, data) {
  return request({ url: `/api/hr/secondments/${id}/hr-review`, method: 'post', data })
}
export function secReviewSign(id, data) {
  return request({ url: `/api/hr/secondments/${id}/review-sign`, method: 'post', data })
}
export function secAdjudicate(id, data) {
  return request({ url: `/api/hr/secondments/${id}/adjudicate`, method: 'post', data })
}
export function secApprove(id, data) {
  return request({ url: `/api/hr/secondments/${id}/approve`, method: 'post', data })
}
export function secEffect(id) {
  return request({ url: `/api/hr/secondments/${id}/effect`, method: 'post' })
}
export function secReturn(id, data) {
  return request({ url: `/api/hr/secondments/${id}/return`, method: 'post', data })
}
export function secExtend(id, data) {
  return request({ url: `/api/hr/secondments/${id}/extend`, method: 'post', data })
}
export function secApproveExtend(id, data) {
  return request({ url: `/api/hr/secondments/${id}/approve-extend`, method: 'post', data })
}
export function secRemindScan() {
  return request({ url: '/api/hr/secondments/remind-scan', method: 'post' })
}
export function secInFlightFlags() {
  return request({ url: '/api/hr/secondments/in-flight-flags', method: 'get' })
}
export function secAllowanceStd(params) {
  return request({ url: '/api/hr/secondments/allowance-standards', method: 'get', params })
}
export function secAllocPreview(params) {
  return request({ url: '/api/hr/secondments/allocations/preview', method: 'get', params })
}
export function secAllocGenerate(data) {
  return request({ url: '/api/hr/secondments/allocations/generate', method: 'post', data })
}

// ---------- 通用附件（最小能力） ----------

export function uploadAttachment(file, bizType, bizId) {
  const form = new FormData()
  form.append('file', file)
  form.append('bizType', bizType)
  form.append('bizId', bizId)
  return request({ url: '/api/common/attachments', method: 'post', data: form })
}
export function attachmentList(bizType, bizId) {
  return request({ url: '/api/common/attachments', method: 'get', params: { bizType, bizId } })
}
export function attachmentDownloadUrl(id) {
  return `/api/common/attachments/download?id=${id}`
}
