import request from '@/utils/request'

// ---------- 5.2.1 工序维护（工序字典） ----------

export function getOperationsApi(params) {
  return request.get('/mrp/operations', { params })
}

export function createOperationApi(data) {
  return request.post('/mrp/operations', data)
}

export function updateOperationApi(id, data) {
  return request.put(`/mrp/operations/${id}`, data)
}

export function operationStatusApi(id, status) {
  return request.post(`/mrp/operations/${id}/status`, null, { params: { status } })
}

// ---------- 5.2.2 工作中心 ----------

export function getWorkCentersApi(params) {
  return request.get('/mrp/work-centers', { params })
}

export function createWorkCenterApi(data) {
  return request.post('/mrp/work-centers', data)
}

export function updateWorkCenterApi(id, data) {
  return request.put(`/mrp/work-centers/${id}`, data)
}

export function workCenterStatusApi(id, status) {
  return request.post(`/mrp/work-centers/${id}/status`, null, { params: { status } })
}

// ---------- 5.2.3 标准工时（定额矩阵） ----------

export function getStandardsApi(params) {
  return request.get('/mrp/op-wc-standards', { params })
}

export function createStandardApi(data) {
  return request.post('/mrp/op-wc-standards', data)
}

export function updateStandardApi(id, data) {
  return request.put(`/mrp/op-wc-standards/${id}`, data)
}

export function deleteStandardApi(id) {
  return request.delete(`/mrp/op-wc-standards/${id}`)
}

// ---------- 5.2.4 路线装配 ----------

export function getRoutingsApi(params) {
  return request.get('/mrp/routings', { params })
}

export function getRoutingDetailApi(id) {
  return request.get(`/mrp/routings/${id}`)
}

export function createRoutingApi(data) {
  return request.post('/mrp/routings', data)
}

export function saveRoutingDraftApi(id, data) {
  return request.put(`/mrp/routings/${id}`, data)
}

export function changeRoutingApi(id, data) {
  return request.post(`/mrp/routings/${id}/change`, data)
}

export function submitRoutingApi(id) {
  return request.post(`/mrp/routings/${id}/submit`)
}

export function obsoleteRoutingApi(id) {
  return request.post(`/mrp/routings/${id}/obsolete`)
}

/** 下游契约：按产品取已发布路线（有序工序行 + 四类工时 + 产能三要素） */
export function getPublishedRouteApi(itemCode) {
  return request.get('/mrp/routings/published', { params: { itemCode } })
}
