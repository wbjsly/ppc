import request from '@/utils/request'

/** 4.8.1 配送线路分页 */
export function getRoutePageApi(params) {
  return request.get('/inv/routes', { params })
}

/** 启用线路全量（客户档案上拉选 / 聚类展示） */
export function getActiveRoutesApi() {
  return request.get('/inv/routes/active')
}

/** 新增线路 */
export function createRouteApi(data) {
  return request.post('/inv/routes', data)
}

/** 编辑线路（编码不可改） */
export function updateRouteApi(id, data) {
  return request.put(`/inv/routes/${id}`, data)
}

/** 停用/启用线路 */
export function changeRouteStatusApi(id, status) {
  return request.post(`/inv/routes/${id}/status`, null, { params: { status } })
}
