import request from '@/utils/request'

/** 资产转资（add-asset-capitalization，spec asset-capitalization） */

// ---- 资产分类主数据（7.1.4）----

export function getCategoryPageApi(params) {
  return request.get('/eam/categories', { params })
}

export function createCategoryApi(data) {
  return request.post('/eam/categories', data)
}

export function updateCategoryApi(id, data) {
  return request.put(`/eam/categories/${id}`, data)
}

export function getActiveCategoryApi(code) {
  return request.get(`/eam/categories/active/${code}`)
}

// ---- 在建工程 CIP 台账（7.1.5）----

export function getCipPageApi(params) {
  return request.get('/eam/cip/projects', { params })
}

export function createCipApi(data) {
  return request.post('/eam/cip/projects', data)
}

export function updateCipApi(id, data) {
  return request.put(`/eam/cip/projects/${id}`, data)
}

export function getCipDetailApi(id) {
  return request.get(`/eam/cip/projects/${id}`)
}

export function addCipCostApi(data) {
  return request.post('/eam/cip/costs', data)
}

export function completeCipApi(id, date) {
  return request.post(`/eam/cip/projects/${id}/complete`, null, { params: { date } })
}

export function getCipCostSummaryApi(id) {
  return request.get(`/eam/cip/projects/${id}/costs/summary`)
}

// ---- 转资申请（7.1.1 采购转资 / 7.1.2 在建转资）----

export function getCapPageApi(params) {
  return request.get('/eam/capitalizations', { params })
}

export function createCapApi(data) {
  return request.post('/eam/capitalizations', data)
}

export function updateCapApi(id, data) {
  return request.put(`/eam/capitalizations/${id}`, data)
}

export function getCapDetailApi(id) {
  return request.get(`/eam/capitalizations/${id}`)
}

export function submitCapApi(id) {
  return request.post(`/eam/capitalizations/${id}/submit`)
}

export function getDepreciationPreviewApi(id) {
  return request.get(`/eam/capitalizations/${id}/depreciation-preview`)
}

/** 待转资清单（超期分级预警） */
export function getPendingListApi() {
  return request.get('/eam/capitalizations/pending')
}

// ---- 资产卡片（7.1.3 卡片生成）----

export function getCardPageApi(params) {
  return request.get('/eam/cards', { params })
}

export function getCardDetailApi(id) {
  return request.get(`/eam/cards/${id}`)
}

export function updateCardApi(id, data) {
  return request.put(`/eam/cards/${id}`, data)
}
