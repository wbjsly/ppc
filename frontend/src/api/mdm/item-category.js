import request from '@/utils/request'

/** 管理页树形（全部状态） */
export function getCategoryTreeApi() {
  return request.get('/mdm/item-categories/tree')
}

/** 物料表单平铺（仅启用）——既有只读接口 */
export function getCategoryListApi() {
  return request.get('/mdm/item-categories')
}

export function createCategoryApi(data) {
  return request.post('/mdm/item-categories', data)
}

export function updateCategoryApi(data) {
  return request.put('/mdm/item-categories', data)
}

export function disableCategoryApi(id) {
  return request.put(`/mdm/item-categories/${id}/disable`)
}

export function getMergeImpactApi(id, targetId) {
  return request.get(`/mdm/item-categories/${id}/merge-impact`, { params: { targetId } })
}

export function mergeCategoryApi(id, targetId, reason, confirmLarge = false) {
  return request.post(`/mdm/item-categories/${id}/merge`, null, {
    params: { targetId, reason, confirmLarge }
  })
}

export function moveCategoryApi(id, targetParentId, reason) {
  return request.put(`/mdm/item-categories/${id}/move`, null, {
    params: { targetParentId, reason }
  })
}

export function getCategoryVersionsApi(id) {
  return request.get(`/mdm/item-categories/${id}/versions`)
}

export function getCategoryDiffApi(id, from, to) {
  return request.get(`/mdm/item-categories/${id}/diff`, { params: { from, to } })
}
