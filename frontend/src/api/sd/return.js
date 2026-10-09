import request from '@/utils/request'

// ---------- 销售退货（spec sales-return，3.10.1~3.10.3） ----------

/** SO 行可退余量（已发 − 已退 + 开票状态，12.2） */
export function getReturnablesApi(soId) {
  return request({ url: '/sd/returns/returnables', method: 'get', params: { soId } })
}

/** 创建退货申请（数量≤可退余量、开票状态分流、凭证说明必填） */
export function createReturnApi(data) {
  return request({ url: '/sd/returns', method: 'post', data })
}

export function getReturnsApi(params) {
  return request({ url: '/sd/returns', method: 'get', params })
}

/** 全链路详情（申请/判定/审批/红冲换货/入库） */
export function getReturnDetailApi(id) {
  return request({ url: `/sd/returns/${id}`, method: 'get' })
}

/** 12.3 判定：责任方/核定数量/处理方式/依据（超期自动标记） */
export function judgeReturnApi(id, data) {
  return request({ url: `/sd/returns/${id}/judge`, method: 'post', data })
}

/** 判定驳回（注明原因退回） */
export function judgeRejectApi(id, reason) {
  return request({ url: `/sd/returns/${id}/judge-reject`, method: 'post', data: { reason } })
}

/** 12.4 提交审批（退款 L2：销售经理+财务；换货：销售经理） */
export function submitReturnApi(id) {
  return request({ url: `/sd/returns/${id}/submit`, method: 'post' })
}

/** 12.5 退款执行（红字发票+应收红冲+凭证，部分退款支付登记） */
export function refundReturnApi(id, data) {
  return request({ url: `/sd/returns/${id}/refund`, method: 'post', data })
}

/** 12.6 换货执行（新发货单重走 ATP 锁批，库存不足阻断） */
export function exchangeReturnApi(id) {
  return request({ url: `/sd/returns/${id}/exchange`, method: 'post' })
}

/** 12.7 实物入库（回补 AVAILABLE_QTY；qc=1 入待检不计 ATP） */
export function stockInReturnApi(id, items) {
  return request({ url: `/sd/returns/${id}/stock-in`, method: 'post', data: { items } })
}

/** 撤销（仅草稿/已驳回） */
export function cancelReturnApi(id, reason) {
  return request({ url: `/sd/returns/${id}/cancel`, method: 'post', data: { reason } })
}
