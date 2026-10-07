import request from '@/utils/request'

// ================= 供应商门户（spec supplier-portal-account 行级隔离） =================

// ---------- 我的 PO（po-collaboration） ----------

export function getMyPosApi(params) {
  return request.get('/portal/pos', { params })
}

export function getMyPoTimelineApi(poId) {
  return request.get(`/portal/pos/${poId}/timeline`)
}

/** 我的 PO 明细（头+行） */
export function getMyPoDetailApi(poId) {
  return request.get(`/portal/pos/${poId}`)
}

/** 交期确认（PORTAL） */
export function confirmMyPoApi(poId, data) {
  return request.post(`/portal/pos/${poId}/confirm`, data || {})
}

/** 改期/改量申请 */
export function changeMyPoApi(poId, data) {
  return request.post(`/portal/pos/${poId}/change-request`, data || {})
}

// ---------- 我的 ASN（asn-collaboration） ----------

export function getMyAsnsApi(params) {
  return request.get('/portal/asns', { params })
}

export function getMyAsnDetailApi(id) {
  return request.get(`/portal/asns/${id}`)
}

/** 按已确认交期的 PO 创建 ASN */
export function createMyAsnApi(data) {
  return request.post('/portal/asns', data)
}

// ---------- 我的 VMI（vmi-portal-sync） ----------

/** 水位快照（加载即同步） */
export function getMyWaterApi() {
  return request.get('/portal/vmi/water')
}

/** 我的补货建议 */
export function getMyAlertsApi(params) {
  return request.get('/portal/vmi/alerts', { params })
}

/** 补货建议确认（联动创建 ASN） */
export function confirmReplenishApi(alertId, data) {
  return request.post(`/portal/vmi/alerts/${alertId}/confirm`, data || {})
}

/** 我的结算单 */
export function getMySettlementsApi(params) {
  return request.get('/portal/vmi/settlements', { params })
}

/** 结算单门户确认（side=supplier） */
export function signMySettlementApi(id, data) {
  return request.post(`/portal/vmi/settlements/${id}/sign`, data || {})
}

// ---------- 我的对账（supplier-statement-reconciliation ADDED） ----------

export function getMyStatementsApi(params) {
  return request.get('/portal/statements', { params })
}

export function getMyStatementDetailApi(id) {
  return request.get(`/portal/statements/${id}`)
}

// ---------- 我的推送记录（portal-event-push） ----------

export function getMyEventsApi(params) {
  return request.get('/portal/events', { params })
}
