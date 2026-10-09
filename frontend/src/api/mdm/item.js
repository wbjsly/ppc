import request from '@/utils/request'

export function getItemPageApi(params) {
  return request.get('/mdm/items', { params })
}

export function getItemApi(id) {
  return request.get(`/mdm/items/${id}`)
}

export function createItemApi(data, forceCreate = false) {
  return request.post('/mdm/items', data, { params: { forceCreate } })
}

export function updateItemApi(data) {
  return request.put('/mdm/items', data)
}

export function disableItemApi(id, reason) {
  return request.put(`/mdm/items/${id}/disable`, null, { params: { reason } })
}

export function getSimilarApi(itemName) {
  return request.get('/mdm/items/similar', { params: { itemName } })
}

export function getItemOptionsApi() {
  return request.get('/mdm/items/options')
}

export function getItemVersionsApi(id) {
  return request.get(`/mdm/items/${id}/versions`)
}

export function getItemDiffApi(id, from, to) {
  return request.get(`/mdm/items/${id}/diff`, { params: { from, to } })
}

export function getCategoriesApi() {
  return request.get('/mdm/item-categories')
}

export function getDictApi(type) {
  return request.get('/mdm/item-dicts', { params: { type } })
}
