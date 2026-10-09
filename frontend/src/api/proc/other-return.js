import request from '@/utils/request'

/** 其他退货（2.6.2 非质量退货，spec other-return）——与 2.6.1 同底座接口，来源固定 MANUAL */

export function getOtherReturnPageApi(params) {
  return request.get('/proc/returns', { params })
}

export function getOtherReturnDetailApi(id) {
  return request.get(`/proc/returns/${id}`)
}

/** PO 可退入库带出：入库凭证/批次/原入库单价/可退库存量（BR-4.2-32） */
export function getPoReturnablesApi(poId) {
  return request.get('/proc/returns/po-returnables', { params: { poId } })
}

/**
 * 非质量退货创建（spec other-return）：
 * body = { poId, itemCode, batchNo, qty, reasonType, note, itemName }
 * 单价/金额/入库凭证由后端反查（D3），前端不传。
 */
export function createOtherReturnApi(data) {
  return request.post('/proc/returns', data)
}

/** 提交采购经理审批 */
export function submitOtherReturnApi(id) {
  return request.post(`/proc/returns/${id}/submit`)
}

/** 作废（仅草稿/被驳回） */
export function cancelOtherReturnApi(id, reason) {
  return request.post(`/proc/returns/${id}/cancel`, { reason })
}

/** 退货出库（红字凭证 RV + 库存扣减 + 30 天跟踪） */
export function postOtherReturnApi(id) {
  return request.post(`/proc/returns/${id}/postings`, {})
}

/** 跟踪闭环 */
export function closeOtherTrackApi(id, remark) {
  return request.post(`/proc/returns/${id}/track-close`, { remark })
}
