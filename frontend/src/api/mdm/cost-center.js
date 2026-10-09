import request from '@/utils/request'

export function getCostCenterTreeApi(params) {
  return request.get('/mdm/cost-centers', { params })
}

export function getCostCenterApi(id) {
  return request.get(`/mdm/cost-centers/${id}`)
}

export function createCostCenterApi(data) {
  return request.post('/mdm/cost-centers', data)
}

export function updateCostCenterApi(data) {
  return request.put('/mdm/cost-centers', data)
}

export function disableCostCenterApi(id) {
  return request.put(`/mdm/cost-centers/${id}/disable`)
}

export function setDefaultCostCenterApi(id) {
  return request.put(`/mdm/cost-centers/${id}/default`)
}

export function getCostCenterOptionsApi(legalEntityId) {
  return request.get('/mdm/cost-centers/options', { params: { legalEntityId } })
}

export function getCostCenterVersionsApi(id) {
  return request.get(`/mdm/cost-centers/${id}/versions`)
}

export function getCostCenterDiffApi(id, from, to) {
  return request.get(`/mdm/cost-centers/${id}/diff`, { params: { from, to } })
}
