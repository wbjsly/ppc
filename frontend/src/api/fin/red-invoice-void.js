import request from '@/utils/request'

// ============ 9.4.1 红字冲销 ============
export function createRedFlush(payload) {
  return request({ url: '/api/fin/red-flush-applies', method: 'post', data: payload })
}

export function submitRedFlush(id) {
  return request({ url: `/api/fin/red-flush-applies/${id}/submit`, method: 'post' })
}

export function redFlushPage(params) {
  return request({ url: '/api/fin/red-flush-applies', method: 'get', params })
}

export function redFlushDetail(id) {
  return request({ url: `/api/fin/red-flush-applies/${id}`, method: 'get' })
}

export function recordInfoSheetNo(id, infoSheetNo) {
  return request({ url: `/api/fin/red-flush-applies/${id}/info-sheet-no`, method: 'put', data: { infoSheetNo } })
}

export function issueRedFlush(id) {
  return request({ url: `/api/fin/red-flush-applies/${id}/issue`, method: 'post' })
}

export function reopenFromRedFlush(id, reopenNote) {
  return request({ url: `/api/fin/red-flush-applies/${id}/reopen`, method: 'post', data: { reopenNote } })
}

// ============ 9.4.2 发票作废 ============
export function applyVoid(payload) {
  return request({ url: '/api/fin/invoice-void-applies', method: 'post', data: payload })
}

export function voidPage(params) {
  return request({ url: '/api/fin/invoice-void-applies', method: 'get', params })
}

export function voidDetail(id) {
  return request({ url: `/api/fin/invoice-void-applies/${id}`, method: 'get' })
}

// ============ 9.4.3 进项转出 ============
export function directTransferOut(payload) {
  return request({ url: '/api/fin/input-tax-transfers/direct', method: 'post', data: payload })
}

export function apportionTransferOut(payload) {
  return request({ url: '/api/fin/input-tax-transfers/apportion', method: 'post', data: payload })
}

// ============ 共享数据源 ============
export function salesInvoiceLedger(params) {
  return request({ url: '/api/fin/sales-invoice-ledger', method: 'get', params })
}

export function inputInvoiceList(params) {
  return request({ url: '/api/fin/input-invoices', method: 'get', params })
}
