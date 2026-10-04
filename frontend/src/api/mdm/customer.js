import request from '@/utils/request'

// ---------- 集团视图 ----------

export function getGroupPageApi(params) {
  return request.get('/mdm/customer-groups', { params })
}

export function getGroupApi(id) {
  return request.get(`/mdm/customer-groups/${id}`)
}

export function createGroupApi(data, forceCreate = false) {
  return request.post('/mdm/customer-groups', data, { params: { forceCreate } })
}

export function updateGroupApi(data) {
  return request.put('/mdm/customer-groups', data)
}

export function getGroupVersionsApi(id) {
  return request.get(`/mdm/customer-groups/${id}/versions`)
}

export function getGroupDiffApi(id, from, to) {
  return request.get(`/mdm/customer-groups/${id}/diff`, { params: { from, to } })
}

// ---------- 状态机 ----------

export function changeStatusApi(id, toStatus, reason) {
  return request.put(`/mdm/customer-groups/${id}/status`, null, { params: { toStatus, reason } })
}

export function freezeApi(id, reason) {
  return request.put(`/mdm/customer-groups/${id}/freeze`, null, { params: { reason } })
}

export function unfreezeApi(id, reason) {
  return request.put(`/mdm/customer-groups/${id}/unfreeze`, null, { params: { reason } })
}

export function getImpactApi(id) {
  return request.get(`/mdm/customer-groups/${id}/impact`)
}

// ---------- 合并 ----------

export function getMergeCandidatesApi(keyword, excludeId) {
  return request.get('/mdm/customer-groups/merge-candidates', { params: { keyword, excludeId } })
}

export function mergeApi(sourceId, targetId, reason) {
  return request.post('/mdm/customer-groups/merge', { sourceId, targetId, reason })
}

// ---------- 法人视图 ----------

export function getViewsApi(groupId) {
  return request.get('/mdm/customer-views', { params: { groupId } })
}

export function saveViewApi(data) {
  return request.post('/mdm/customer-views', data)
}

export function getViewVersionsApi(id) {
  return request.get(`/mdm/customer-views/${id}/versions`)
}
