import request from '@/utils/request'

// 税码匹配（唯一自动选定/多命中候选/C-4.14-01 422）
export function matchTaxCode(params) {
  return request({ url: '/api/fin/tax-calc/match', method: 'get', params })
}

// 税额计算（价税分离/多税率折扣分摊/简易不抵扣/尾差 overTolerance）
export function calcTax(payload) {
  return request({ url: '/api/fin/tax-calc/calc', method: 'post', data: payload })
}

// 台账
export function ledgerList(params) {
  return request({ url: '/api/fin/tax-calc/ledger', method: 'get', params })
}

export function createLedger(payload) {
  return request({ url: '/api/fin/tax-calc/ledger', method: 'post', data: payload })
}

export function submitApply(id, payload) {
  return request({ url: `/api/fin/tax-calc/ledger/${id}/submit-apply`, method: 'post', data: payload })
}

export function generateVoucher(id) {
  return request({ url: `/api/fin/tax-calc/ledger/${id}/generate-voucher`, method: 'post' })
}

// 合规校验报告
export function complianceReport(period) {
  return request({ url: '/api/fin/tax-calc/report', method: 'get', params: { period } })
}
