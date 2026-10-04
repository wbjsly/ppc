import request from '@/utils/request'

/** 紧急申请分页（后端查询前懒 sweep） */
export function getEmergencyPageApi(params) {
  return request.get('/proc/emergency', { params })
}

export function getEmergencyDetailApi(id) {
  return request.get(`/proc/emergency/${id}`)
}

/** PR 候选（已批准/待询价） */
export function getPrCandidatesApi(params) {
  return request.get('/proc/emergency/pr-candidates', { params })
}

/** 发起（通道/PR/防重三重前置） */
export function createEmergencyApi(data) {
  return request.post('/proc/emergency', data)
}

export function approveEmergencyApi(id, reason) {
  return request.post(`/proc/emergency/${id}/approve`, { reason })
}

export function rejectEmergencyApi(id, reason) {
  return request.post(`/proc/emergency/${id}/reject`, { reason })
}

export function fillEmergencyApi(id, data) {
  return request.post(`/proc/emergency/${id}/fill`, data)
}

export function closeEmergencyApi(id, reason) {
  return request.post(`/proc/emergency/${id}/close`, { reason })
}

/** RFQ/PO 放行桩 */
export function getClearanceApi(prNo) {
  return request.get('/proc/emergency/clearance', { params: { prNo } })
}

/** 通道台账 */
export function getChannelsApi(params) {
  return request.get('/proc/emergency-channels', { params })
}

export function restoreChannelApi(account, note) {
  return request.post('/proc/emergency-channels/restore', { account, note })
}
