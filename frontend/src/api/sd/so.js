import request from '@/utils/request'

// ---------- 销售订单（spec sales-order，3.5.1~3.5.4） ----------

export function getSoPageApi(params) {
  return request({ url: '/sd/so', method: 'get', params })
}

export function getSoDetailApi(id) {
  return request({ url: `/sd/so/${id}`, method: 'get' })
}

export function getSoApprovalLogsApi(id) {
  return request({ url: `/sd/so/${id}/approval-logs`, method: 'get' })
}

export function createSoApi(data) {
  return request({ url: '/sd/so', method: 'post', data })
}

export function updateSoApi(data) {
  return request({ url: '/sd/so', method: 'put', data })
}

/** 手动改价（锁行 + 价格变更审批，BR-4.3-26） */
export function changeSoLinePriceApi(lineId, unitPrice, reason) {
  return request({ url: `/sd/so/lines/${lineId}/price`, method: 'post', data: { unitPrice, reason } })
}

/** 提交审批：金额三档 + 链尾加签；小额自动确认（FR-4.3-4-5） */
export function submitSoApi(id) {
  return request({ url: `/sd/so/${id}/submit`, method: 'post' })
}

/** 付款条件差异的销售主管确认（BR-4.3-25） */
export function confirmPaymentTermsApi(id, reason) {
  return request({ url: `/sd/so/${id}/confirm-payment-terms`, method: 'post', data: { reason } })
}

/** 行交期客户确认回执（BR-4.3-28） */
export function ackDeliveryApi(lineId) {
  return request({ url: `/sd/so/lines/${lineId}/ack-delivery`, method: 'post' })
}

/** 订单变更（重跑信用/ATP，已执行行阻断，FR-4.3-4-7） */
export function changeSoApi(id, lines, reason) {
  return request({ url: `/sd/so/${id}/change`, method: 'put', data: { lines, reason } })
}

/** 订单关闭（释放预留，销售经理确认，3.5.4） */
export function closeSoApi(id, reason) {
  return request({ url: `/sd/so/${id}/close`, method: 'post', data: { reason } })
}
