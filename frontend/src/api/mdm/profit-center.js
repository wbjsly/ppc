import request from '@/utils/request'

export function getProfitCenterPageApi(params) {
  return request.get('/mdm/profit-centers', { params })
}

export function getProfitCenterApi(id) {
  return request.get(`/mdm/profit-centers/${id}`)
}

export function createProfitCenterApi(data) {
  return request.post('/mdm/profit-centers', data)
}

export function updateProfitCenterApi(data) {
  return request.put('/mdm/profit-centers', data)
}

export function disableProfitCenterApi(id) {
  return request.put(`/mdm/profit-centers/${id}/disable`)
}

export function getProfitCenterOptionsApi(legalEntityId) {
  return request.get('/mdm/profit-centers/options', { params: { legalEntityId } })
}

export function getProfitCenterVersionsApi(id) {
  return request.get(`/mdm/profit-centers/${id}/versions`)
}

export function getProfitCenterDiffApi(id, from, to) {
  return request.get(`/mdm/profit-centers/${id}/diff`, { params: { from, to } })
}
