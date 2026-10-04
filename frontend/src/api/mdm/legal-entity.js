import request from '@/utils/request'

export function getLegalEntityPageApi(params) {
  return request.get('/mdm/legal-entities', { params })
}

export function getLegalEntityApi(id) {
  return request.get(`/mdm/legal-entities/${id}`)
}

export function createLegalEntityApi(data) {
  return request.post('/mdm/legal-entities', data)
}

export function updateLegalEntityApi(data) {
  return request.put('/mdm/legal-entities', data)
}

export function disableLegalEntityApi(id) {
  return request.put(`/mdm/legal-entities/${id}/disable`)
}

export function getLegalEntityOptionsApi() {
  return request.get('/mdm/legal-entities/options')
}

export function getVersionsApi(id) {
  return request.get(`/mdm/legal-entities/${id}/versions`)
}

export function getDiffApi(id, from, to) {
  return request.get(`/mdm/legal-entities/${id}/diff`, { params: { from, to } })
}
