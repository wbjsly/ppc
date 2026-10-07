import request from '@/utils/request'

// ---------- 线索 ----------
export function getLeadsApi(params) {
  return request.get('/crm/leads', { params })
}

export function getLeadApi(id) {
  return request.get(`/crm/leads/${id}`)
}

/** 查重（FR-4.8-1-1）：返回既有线索候选 */
export function checkDuplicateApi(data) {
  return request.post('/crm/leads/check-duplicate', data)
}

export function createLeadApi(data) {
  return request.post('/crm/leads', data)
}

export function updateLeadApi(data) {
  return request.put('/crm/leads', data)
}

export function assignLeadApi(id, ownerId, ownerName) {
  return request.put(`/crm/leads/${id}/assign`, null, { params: { ownerId, ownerName } })
}

export function claimLeadApi(id) {
  return request.put(`/crm/leads/${id}/claim`)
}

export function toPoolApi(id, reason, remark) {
  return request.put(`/crm/leads/${id}/pool`, null, { params: { reason, remark } })
}

export function scoreLeadApi(id, body) {
  return request.post(`/crm/leads/${id}/score`, body)
}

export function sweepLeadsApi() {
  return request.post('/crm/leads/sweep')
}

// ---------- 跟进（仅追加） ----------
export function getFollowupsApi(leadId) {
  return request.get(`/crm/leads/${leadId}/followups`)
}

export function addFollowupApi(leadId, data) {
  return request.post(`/crm/leads/${leadId}/followups`, data)
}

// ---------- 线索池 ----------
export function getLeadPoolApi(status) {
  return request.get('/crm/lead-pool', { params: { status } })
}

export function assignFromPoolApi(poolId, ownerId, ownerName) {
  return request.put(`/crm/lead-pool/${poolId}/assign`, null, { params: { ownerId, ownerName } })
}

// ---------- 评分模型（11.1.2 模型配置 Tab） ----------
export function getModelsApi(params) {
  return request.get('/crm/lead-models', { params })
}

export function getActiveModelApi(params) {
  return request.get('/crm/lead-models/active', { params })
}

export function getModelVersionsApi(key) {
  return request.get(`/crm/lead-models/${key}/versions`)
}

export function saveModelApi(data) {
  return request.post('/crm/lead-models', data)
}
