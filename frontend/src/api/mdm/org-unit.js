import request from '@/utils/request'

export function getOrgUnitTreeApi(params) {
  return request.get('/mdm/org-units', { params })
}

export function getOrgUnitApi(id) {
  return request.get(`/mdm/org-units/${id}`)
}

export function createOrgUnitApi(data) {
  return request.post('/mdm/org-units', data)
}

export function updateOrgUnitApi(data) {
  return request.put('/mdm/org-units', data)
}

export function disableOrgUnitApi(id) {
  return request.put(`/mdm/org-units/${id}/disable`)
}

export function getOrgUnitOptionsApi(legalEntityId) {
  return request.get('/mdm/org-units/options', { params: { legalEntityId } })
}

export function getOrgUnitVersionsApi(id) {
  return request.get(`/mdm/org-units/${id}/versions`)
}

export function getOrgUnitDiffApi(id, from, to) {
  return request.get(`/mdm/org-units/${id}/diff`, { params: { from, to } })
}
