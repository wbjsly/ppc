import request from '@/utils/request'

// ============ 9.2.2 进项发票 ============
export function registerInput(payload) {
  return request({ url: '/api/fin/input-invoices', method: 'post', data: payload })
}

export function inputList(params) {
  return request({ url: '/api/fin/input-invoices', method: 'get', params })
}

export function inspectInput(id, payload) {
  return request({ url: `/api/fin/input-invoices/${id}/inspect`, method: 'post', data: payload })
}

export function certifyInput(id, payload) {
  return request({ url: `/api/fin/input-invoices/${id}/certify`, method: 'post', data: payload })
}

export function generateInputVouchers(period) {
  return request({ url: '/api/fin/input-invoices/generate-vouchers', method: 'post', params: { period } })
}

export function nearingExpiry() {
  return request({ url: '/api/fin/input-invoices/nearing-expiry', method: 'get' })
}

// ============ 9.2.3 数电发票 ============
export function saveQuota(payload) {
  return request({ url: '/api/fin/digital-invoices/quota', method: 'post', data: payload })
}

export function getQuota(period) {
  return request({ url: '/api/fin/digital-invoices/quota', method: 'get', params: { period } })
}

export function submitRequest(payload) {
  return request({ url: '/api/fin/digital-invoices/requests', method: 'post', data: payload })
}

export function listRequests(status) {
  return request({ url: '/api/fin/digital-invoices/requests', method: 'get', params: { status } })
}

export function issueRequest(id) {
  return request({ url: `/api/fin/digital-invoices/requests/${id}/issue`, method: 'post' })
}

export function reconcile(period) {
  return request({ url: '/api/fin/digital-invoices/reconcile', method: 'get', params: { period } })
}

// ============ 9.2.4 发票交付 ============
export function registerDelivery(payload) {
  return request({ url: '/api/fin/invoice-deliveries', method: 'post', data: payload })
}

export function deliveryList(params) {
  return request({ url: '/api/fin/invoice-deliveries', method: 'get', params })
}

export function markSent(id) {
  return request({ url: `/api/fin/invoice-deliveries/${id}/mark-sent`, method: 'post' })
}

export function resendDelivery(id) {
  return request({ url: `/api/fin/invoice-deliveries/${id}/resend`, method: 'post' })
}

// ============ 9.2.1 销项台账 ============
export function salesLedger(params) {
  return request({ url: '/api/fin/sales-invoice-ledger', method: 'get', params })
}

export function limitCheck(amount) {
  return request({ url: '/api/fin/sales-invoice-ledger/limit-check', method: 'get', params: { amount } })
}
