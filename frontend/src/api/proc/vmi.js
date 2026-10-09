import request from '@/utils/request'

/** 采购协同-寄售采购（2.8.1，spec vmi-consignment） */

// ---------------- VMI 协议 ----------------

export function getAgreementPageApi(params) {
  return request.get('/proc/vmi/agreements', { params })
}

export function getAgreementDetailApi(id) {
  return request.get(`/proc/vmi/agreements/${id}`)
}

/** 创建协议（头 + 物料清单行：itemCode/unit/minQty/maxQty/unitPrice/priceStart/priceEnd） */
export function createAgreementApi(data) {
  return request.post('/proc/vmi/agreements', data)
}

export function updateAgreementApi(id, data) {
  return request.put(`/proc/vmi/agreements/${id}`, data)
}

/** 生效（DRAFT → EFFECTIVE） */
export function effectiveAgreementApi(id) {
  return request.post(`/proc/vmi/agreements/${id}/effective`, {})
}

/** 停用 */
export function disableAgreementApi(id) {
  return request.post(`/proc/vmi/agreements/${id}/disable`, {})
}

// ---------------- 寄售库存台账 / 告警 ----------------

/** 台账数据源（复用告警页的库存列表由后端另行提供，此处为补货建议扫描） */
export function scanReplenishApi() {
  return request.post('/proc/vmi/alerts/scan', {})
}

export function getAlertPageApi(params) {
  return request.get('/proc/vmi/alerts', { params })
}

/** 超水位告警确认放行（ADMIN/PM，OPEN → CONFIRMED 留痕） */
export function confirmAlertApi(id, opinion) {
  return request.post(`/proc/vmi/alerts/${id}/confirm`, { opinion })
}

// ---------------- 寄售库存台账（分页查询走 alerts 之外的独立端点由后端提供） ----------------

export function getVmiStockApi(params) {
  return request.get('/proc/vmi/stocks', { params })
}

// ---------------- VMI 结算 ----------------

export function getSettlementPageApi(params) {
  return request.get('/proc/vmi/settlements', { params })
}

export function getSettlementDetailApi(id) {
  return request.get(`/proc/vmi/settlements/${id}`)
}

/** 生成结算单（供应商 + 期间聚合领用明细） */
export function createSettlementApi(data) {
  return request.post('/proc/vmi/settlements', data)
}

/** 确认签署：side=pm（采购员）| side=supplier（供应商线下登记，带 supplierConfirmBy/way） */
export function signSettlementApi(id, data) {
  return request.post(`/proc/vmi/settlements/${id}/confirm`, data)
}

// ---------------- 账龄处置建议单 ----------------

export function getDisposalPageApi(params) {
  return request.get('/proc/vmi/disposals', { params })
}

export function scanDisposalApi() {
  return request.post('/proc/vmi/disposals/scan', {})
}

export function resolveDisposalApi(id, data) {
  return request.post(`/proc/vmi/disposals/${id}/resolve`, data)
}

export function cancelDisposalApi(id, reason) {
  return request.post(`/proc/vmi/disposals/${id}/cancel`, { reason })
}
