import request from '@/utils/request'

export function getWarehousesApi(params) {
  return request.get('/inv/warehouses', { params })
}

export function getEnabledWarehousesApi() {
  return request.get('/inv/warehouses/enabled')
}

export function getWarehouseApi(id) {
  return request.get(`/inv/warehouses/${id}`)
}

export function createWarehouseApi(data) {
  return request.post('/inv/warehouses', data)
}

export function updateWarehouseApi(data) {
  return request.put('/inv/warehouses', data)
}

export function enableWarehouseApi(id) {
  return request.put(`/inv/warehouses/${id}/enable`)
}

export function disableWarehouseApi(id) {
  return request.put(`/inv/warehouses/${id}/disable`)
}
