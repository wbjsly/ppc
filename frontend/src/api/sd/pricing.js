import request from '@/utils/request'

// ---------- 价格折扣与治理（3.6.1~3.6.4） ----------

// --- 渠道折扣（3.6.2 Tab） ---
export function getChannelsApi(params) {
  return request.get('/sd/pricing/channels', { params })
}

export function saveChannelApi(data) {
  return request.post('/sd/pricing/channels', data)
}

export function stopChannelApi(id, reason) {
  return request.post(`/sd/pricing/channels/${id}/stop`, { reason })
}

export function enableChannelApi(id) {
  return request.post(`/sd/pricing/channels/${id}/enable`)
}

// --- 促销活动（3.6.2 Tab） ---
export function getPromotionsApi(params) {
  return request.get('/sd/pricing/promotions', { params })
}

export function savePromotionApi(data) {
  return request.post('/sd/pricing/promotions', data)
}

export function publishPromotionApi(id) {
  return request.post(`/sd/pricing/promotions/${id}/publish`)
}

export function stopPromotionApi(id, reason) {
  return request.post(`/sd/pricing/promotions/${id}/stop`, { reason })
}

// --- 叠加规则参数（3.6.2 Tab） ---
export function getStackModeApi() {
  return request.get('/sd/pricing/stack-mode')
}

export function setStackModeApi(mode, operator) {
  return request.put('/sd/pricing/stack-mode', { mode, operator })
}

// --- 试算与矩阵 ---
export function calcPriceApi(data) {
  return request.post('/sd/pricing/calc', data)
}

/** 3.6.1 价格矩阵：客户 × SKU 最终价与命中来源（只读） */
export function getMatrixApi(customerId, itemCodes, qty) {
  return request.get('/sd/pricing/matrix', { params: { customerId, itemCodes, qty } })
}

// --- 3.6.4 取价记录 ---
export function getAuditApi(params) {
  return request.get('/sd/pricing/audit', { params })
}

// --- 特殊价格审批单（5.6 放行凭据） ---
export function getSpecialsApi(params) {
  return request.get('/sd/pricing/special-prices', { params })
}

export function applySpecialApi(data) {
  return request.post('/sd/pricing/special-prices', data)
}

export function submitSpecialApi(id) {
  return request.post(`/sd/pricing/special-prices/${id}/submit`)
}
