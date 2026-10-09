import request from '@/utils/request'

/** 创建调拨单（行内部转移价必填；spec transfer-order） */
export function createTransferApi(data) {
  return request.post('/inv/transfers', data)
}

export function updateTransferApi(id, data) {
  return request.put(`/inv/transfers/${id}`, data)
}

export function cancelTransferApi(id, reason) {
  return request.post(`/inv/transfers/${id}/cancel`, null, { params: { reason } })
}

/** 出库段过账（DRAFT → OUT_POSTED，跨法人带凭证+内部发票） */
export function postTransferOutApi(id) {
  return request.post(`/inv/transfers/${id}/post-out`)
}

/** 入库段过账（OUT_POSTED → IN_POSTED） */
export function postTransferInApi(id) {
  return request.post(`/inv/transfers/${id}/post-in`)
}

/** 关闭（IN_POSTED → CLOSED，跨法人先过核销校验） */
export function closeTransferApi(id) {
  return request.post(`/inv/transfers/${id}/close`)
}

export function getTransferApi(id) {
  return request.get(`/inv/transfers/${id}`)
}

/** 4.12.1 调拨单列表 */
export function getTransfersApi(params) {
  return request.get('/inv/transfers', { params })
}

/** 4.12.2 在途跟踪（OUT_POSTED + 在途天数 + 挂起标记） */
export function getIntransitApi(params) {
  return request.get('/inv/transfers/intransit', { params })
}

/** F2 内部往来视图（ISSUED 未核销按法人对+方向汇总） */
export function getInternalLedgerApi() {
  return request.get('/inv/transfers/internal-ledger')
}

/** F2 逐单下钻 */
export function getInternalLedgerDetailApi(params) {
  return request.get('/inv/transfers/internal-ledger/detail', { params })
}
