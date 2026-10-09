import request from '@/utils/request'

/** 出库作业台队列（spec outbound-workbench：四类型归一化行 + A/B 队列） */
export function getOutboundQueueApi(type, params) {
  return request.get(`/inv/outbound/${type}/queue`, { params })
}

/** 过账（委托域服务：发货/领料/调拨出库/报废，同一后端动作） */
export function postOutboundApi(type, id) {
  return request.post(`/inv/outbound/${type}/${id}/post`)
}

/** 发货确认（仅 SALES_OUT，POSTED → CONFIRMED，委托 Shipment.confirm） */
export function confirmOutboundApi(id, params) {
  return request.post(`/inv/outbound/SALES_OUT/${id}/confirm`, null, { params })
}
