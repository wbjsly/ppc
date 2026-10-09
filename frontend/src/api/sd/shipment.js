import request from '@/utils/request'

// ---------- 销售发货（spec sales-shipment，3.7.1~3.7.4） ----------

export function getShipmentsApi(params) {
  return request({ url: '/sd/shipments', method: 'get', params })
}

export function getShipmentDetailApi(id) {
  return request({ url: `/sd/shipments/${id}`, method: 'get' })
}

/** 部分发货：按 SO 行未发余量（9.2） */
export function generatePartialApi(soId, lines) {
  return request({ url: '/sd/shipments/partial', method: 'post', data: { soId, lines } })
}

/** 合并发货：同客户同仓多 SO（9.3） */
export function generateMergeApi(soIds, warehouseCode) {
  return request({ url: '/sd/shipments/merge', method: 'post', data: { soIds, warehouseCode } })
}

/** 分批发货：按分批方案行（9.4） */
export function generateBatchApi(soId, asOfDate) {
  return request({ url: '/sd/shipments/batch', method: 'post', data: { soId, asOfDate } })
}

/** 出库过账：FIFO 选批扣库存 + 消耗预留 + AR.CONFIRMED（9.6） */
export function postShipmentApi(id) {
  return request({ url: `/sd/shipments/${id}/post`, method: 'post' })
}

/** 发货确认：回写 SO 行 + 物流单号 + 异常通知（9.7） */
export function confirmShipmentApi(id, data) {
  return request({ url: `/sd/shipments/${id}/confirm`, method: 'post', data })
}

/** 签收（9.8） */
export function signShipmentApi(id) {
  return request({ url: `/sd/shipments/${id}/sign`, method: 'post' })
}

/** 拒收 → 退货申请草稿（9.8） */
export function rejectShipmentApi(id, reason) {
  return request({ url: `/sd/shipments/${id}/reject`, method: 'post', data: { reason } })
}

/** 超时未签收预警扫描 */
export function sweepSignTimeoutApi() {
  return request({ url: '/sd/shipments/sweep-sign-timeout', method: 'post' })
}

/** 取消（仅草稿，9.9） */
export function cancelShipmentApi(id, reason) {
  return request({ url: `/sd/shipments/${id}/cancel`, method: 'post', data: { reason } })
}
