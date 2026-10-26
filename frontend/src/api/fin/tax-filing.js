import request from '@/utils/request'

// ============ 9.5.1 增值申报（含留抵退税） ============
export function taxSummarize(period) {
  return request({ url: '/api/fin/tax-returns/summarize', method: 'get', params: { period } })
}

export function taxReconcile(period) {
  return request({ url: '/api/fin/tax-returns/reconcile', method: 'get', params: { period } })
}

export function taxGenerate(period) {
  return request({ url: '/api/fin/tax-returns/generate', method: 'post', data: { period } })
}

export function taxGenerateCit(payload) {
  return request({ url: '/api/fin/tax-returns/generate-cit', method: 'post', data: payload })
}

export function taxSubmit(id) {
  return request({ url: `/api/fin/tax-returns/${id}/submit`, method: 'post' })
}

export function taxRegister(id, payload) {
  return request({ url: `/api/fin/tax-returns/${id}/register`, method: 'post', data: payload })
}

export function taxMarkOverdue(id, faultNote) {
  return request({ url: `/api/fin/tax-returns/${id}/mark-overdue`, method: 'post', data: { faultNote } })
}

export function taxAdjust(id, payload) {
  return request({ url: `/api/fin/tax-returns/${id}/adjust`, method: 'post', data: payload })
}

export function taxPage(params) {
  return request({ url: '/api/fin/tax-returns', method: 'get', params })
}

export function taxDetail(id) {
  return request({ url: `/api/fin/tax-returns/${id}`, method: 'get' })
}

export function taxArchive(period) {
  return request({ url: '/api/fin/tax-returns/archive', method: 'get', params: { period } })
}

// ============ 留抵退税（9.5.1 页内入口） ============
export function ldtiApply(payload) {
  return request({ url: '/api/fin/tax-refunds', method: 'post', data: payload })
}

export function ldtiSubmit(id) {
  return request({ url: `/api/fin/tax-refunds/${id}/submit`, method: 'post' })
}

export function ldtiRegister(id, payload) {
  return request({ url: `/api/fin/tax-refunds/${id}/register`, method: 'post', data: payload })
}

export function ldtiReceive(id, payload) {
  return request({ url: `/api/fin/tax-refunds/${id}/receive`, method: 'post', data: payload })
}

export function ldtiPage(params) {
  return request({ url: '/api/fin/tax-refunds', method: 'get', params })
}

// ============ 9.5.3 出口退税 ============
export function expRegisterDeal(payload) {
  return request({ url: '/api/fin/export-refunds/deals', method: 'post', data: payload })
}

export function expDealPage(params) {
  return request({ url: '/api/fin/export-refunds/deals', method: 'get', params })
}

export function expCheckDocs(id) {
  return request({ url: `/api/fin/export-refunds/deals/${id}/documents`, method: 'get' })
}

export function expCollectDocs(id, payload) {
  return request({ url: `/api/fin/export-refunds/deals/${id}/collect`, method: 'post', data: payload })
}

export function expExemption(id, reason) {
  return request({ url: `/api/fin/export-refunds/deals/${id}/exemption`, method: 'post', data: { reason } })
}

export function expCreateApply(payload) {
  return request({ url: '/api/fin/export-refunds', method: 'post', data: payload })
}

export function expSubmit(id) {
  return request({ url: `/api/fin/export-refunds/${id}/submit`, method: 'post' })
}

export function expRegister(id, payload) {
  return request({ url: `/api/fin/export-refunds/${id}/register`, method: 'post', data: payload })
}

export function expReceive(id, payload) {
  return request({ url: `/api/fin/export-refunds/${id}/receive`, method: 'post', data: payload })
}

export function expApplyPage(params) {
  return request({ url: '/api/fin/export-refunds', method: 'get', params })
}

export function expApplyDetail(id) {
  return request({ url: `/api/fin/export-refunds/${id}`, method: 'get' })
}

export function expWarnings() {
  return request({ url: '/api/fin/export-refunds/warnings', method: 'get' })
}
