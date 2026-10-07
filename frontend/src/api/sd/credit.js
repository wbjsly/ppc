import request from '@/utils/request'

// ---------- 客户信用（3.3.1 信用检查 / 3.3.2 冻结看板 / 3.3.3 预收处理） ----------

/** 实时信用检查（四因子 + 账龄 + 及时率） */
export function checkCreditApi(data) {
  return request.post('/sd/credit/check', data)
}

export function getChecksApi(params) {
  return request.get('/sd/credit/checks', { params })
}

/** SO 信用重检（建单即检 / 变更重跑 BR-4.3-30） */
export function recheckSoApi(soId) {
  return request.post(`/sd/credit/recheck-so/${soId}`)
}

// ---------- 3.3.2 冻结看板 ----------
export function getFreezesApi(params) {
  return request.get('/sd/credit/freezes', { params })
}

export function getFreezeApi(id) {
  return request.get(`/sd/credit/freezes/${id}`)
}

export function unfreezeApi(id, method, remark) {
  return request.post(`/sd/credit/freezes/${id}/unfreeze`, { method, remark })
}

/** 信用特批（上限校验 + 理由必填 BR-4.3-18） */
export function specialApproveApi(id, amount, reason) {
  return request.post(`/sd/credit/freezes/${id}/special-approve`, { amount, reason })
}

// ---------- 3.3.3 预收处理 ----------
export function getNoticesApi(params) {
  return request.get('/sd/credit/notices', { params })
}

export function notifyNoticeApi(id) {
  return request.post(`/sd/credit/notices/${id}/notify`)
}

export function registerReceivedApi(id, amount, remark) {
  return request.post(`/sd/credit/notices/${id}/register`, { amount, remark })
}

/** 财务确认到账（仅 FINANCE_MGR/ADMIN）：足额自动解冻 */
export function confirmReceivedApi(id) {
  return request.post(`/sd/credit/notices/${id}/confirm`)
}
