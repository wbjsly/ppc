import request from '@/utils/request'

// ---------- PO 协同（spec po-collaboration） ----------

/** PO 协同列表（含惰性超时补写） */
export function getPoCoopPageApi(params) {
  return request.get('/proc/po-coops', { params })
}

/** 协同时间线（推送/催办/升级/确认/锁定） */
export function getPoCoopTimelineApi(poId) {
  return request.get(`/proc/po-coops/${poId}/timeline`)
}

/** 催办补发（ADMIN/PM） */
export function remindPoApi(poId) {
  return request.post(`/proc/po-coops/${poId}/remind`, {})
}

/** 代录交期确认（ADMIN/PM，OFFLINE） */
export function offlineConfirmPoApi(poId, data) {
  return request.post(`/proc/po-coops/${poId}/confirm`, data || {})
}

/** 采购员接受改期（ADMIN/PM） */
export function acceptPoChangeApi(poId, data) {
  return request.post(`/proc/po-coops/${poId}/change-accept`, data || {})
}

/** 解锁供应商确认入口（ADMIN） */
export function unlockPoSupplierApi(data) {
  return request.post('/proc/po-coops/unlocks', data)
}

// ---------- ASN（spec asn-collaboration） ----------

export function getAsnPageApi(params) {
  return request.get('/proc/asns', { params })
}

export function getAsnDetailApi(id) {
  return request.get(`/proc/asns/${id}`)
}

/** 代录创建 ASN（ADMIN/PM） */
export function createAsnApi(data) {
  return request.post('/proc/asns', data)
}

// ---------- VMI 水位同步（spec vmi-portal-sync） ----------

/** 触发水位同步（惰性，生成 WATER_SYNCED 推送批次） */
export function triggerWaterSyncApi(data) {
  return request.post('/proc/vmi/water-sync', data || {})
}

// ---------- 推送台账（spec portal-event-push） ----------

export function getPortalEventLedgerApi(params) {
  return request.get('/proc/portal-events', { params })
}

// ---------- 对账系统匹配（spec supplier-statement-reconciliation ADDED） ----------

export function matchStatementApi(id, data) {
  return request.post(`/fin/statements/${id}/match`, data || {})
}
