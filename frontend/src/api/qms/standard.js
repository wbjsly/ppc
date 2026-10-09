import request from '@/utils/request'

/** 检验标准库（6.1，spec inspection-standard） */

export function getStandardsApi(params) {
  return request.get('/qms/standards', { params })
}

export function createStandardApi(data) {
  return request.post('/qms/standards', data)
}

export function updateStandardApi(id, data) {
  return request.put(`/qms/standards/${id}`, data)
}

export function getStandardDetailApi(id) {
  return request.get(`/qms/standards/${id}`)
}

export function retireStandardApi(id) {
  return request.post(`/qms/standards/${id}/retire`)
}

export function createVersionApi(standardId, data) {
  return request.post(`/qms/standards/${standardId}/versions`, data)
}

export function updateVersionApi(versionId, data) {
  return request.put(`/qms/standards/versions/${versionId}`, data)
}

export function publishVersionApi(versionId) {
  return request.post(`/qms/standards/versions/${versionId}/publish`)
}

export function getCharacteristicsApi(versionId) {
  return request.get(`/qms/standards/versions/${versionId}/characteristics`)
}

/** 适用范围命中解析（任务生成快照用） */
export function resolveStandardApi(params) {
  return request.get('/qms/standards/resolve', { params })
}

export function getSamplingPlansApi() {
  return request.get('/qms/standards/sampling-plans')
}

export function saveSamplingPlanApi(data) {
  return request.post('/qms/standards/sampling-plans', data)
}
