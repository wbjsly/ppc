import request from '@/utils/request'

// ============ 9.6.2 优惠备案（tax-preference-register） ============

export function prefList(params) {
  return request({ url: '/api/fin/tax-preferences', method: 'get', params })
}
export function prefRegister(data) {
  return request({ url: '/api/fin/tax-preferences', method: 'post', data })
}
export function prefWarnings() {
  return request({ url: '/api/fin/tax-preferences/warnings', method: 'get' })
}
export function prefDetail(id) {
  return request({ url: `/api/fin/tax-preferences/${id}`, method: 'get' })
}
export function prefRenew(id, data) {
  return request({ url: `/api/fin/tax-preferences/${id}/renew`, method: 'put', data })
}
export function prefFreeze(id, data) {
  return request({ url: `/api/fin/tax-preferences/${id}/freeze`, method: 'put', data })
}
export function prefRevoke(id, data) {
  return request({ url: `/api/fin/tax-preferences/${id}/revoke`, method: 'put', data })
}

// ============ 9.6.1 风险预警（tax-risk-monitoring） ============

export function riskDashboard(params) {
  return request({ url: '/api/fin/tax-risks/dashboard', method: 'get', params })
}
export function certList(params) {
  return request({ url: '/api/fin/tax-risks/certs', method: 'get', params })
}
export function certRegister(data) {
  return request({ url: '/api/fin/tax-risks/certs', method: 'post', data })
}
export function certUpdate(id, data) {
  return request({ url: `/api/fin/tax-risks/certs/${id}`, method: 'put', data })
}
export function certWarnings() {
  return request({ url: '/api/fin/tax-risks/certs/warnings', method: 'get' })
}
export function offlineList() {
  return request({ url: '/api/fin/tax-risks/offline-invoices', method: 'get' })
}
export function offlineRegister(data) {
  return request({ url: '/api/fin/tax-risks/offline-invoices', method: 'post', data })
}
export function offlineUpload(id) {
  return request({ url: `/api/fin/tax-risks/offline-invoices/${id}/upload`, method: 'put' })
}
export function offlineAlerts() {
  return request({ url: '/api/fin/tax-risks/offline-invoices/alerts', method: 'get' })
}
export function burdenMonthly(params) {
  return request({ url: '/api/fin/tax-risks/tax-burdens', method: 'get', params })
}
export function burdenSignals(params) {
  return request({ url: '/api/fin/tax-risks/tax-burdens/signals', method: 'get', params })
}

// ============ 9.6.3 关联定价（transfer-pricing） ============

export function partyList() {
  return request({ url: '/api/fin/tp/parties', method: 'get' })
}
export function partyRegister(data) {
  return request({ url: '/api/fin/tp/parties', method: 'post', data })
}
export function docList(params) {
  return request({ url: '/api/fin/tp/documents', method: 'get', params })
}
export function docRegister(data) {
  return request({ url: '/api/fin/tp/documents', method: 'post', data })
}
export function tpReminders() {
  return request({ url: '/api/fin/tp/reminders', method: 'get' })
}
export function assessmentList() {
  return request({ url: '/api/fin/tp/assessments', method: 'get' })
}
export function assessmentSubmit(data) {
  return request({ url: '/api/fin/tp/assessments', method: 'post', data })
}
