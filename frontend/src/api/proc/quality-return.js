import request from '@/utils/request'

/** 退货单（2.6.1 质量退货，spec quality-return） */

export function getReturnPageApi(params) {
  return request.get('/proc/returns', { params })
}

export function getReturnDetailApi(id) {
  return request.get(`/proc/returns/${id}`)
}

/** 手工发起（已入库须关联原入库单 + 原入库单价） */
export function createReturnApi(data) {
  return request.post('/proc/returns', data)
}

/** 提交采购经理审批 */
export function submitReturnApi(id) {
  return request.post(`/proc/returns/${id}/submit`)
}

/** 作废（仅草稿/被驳回） */
export function cancelReturnApi(id, reason) {
  return request.post(`/proc/returns/${id}/cancel`, { reason })
}

/** 退货出库（红字凭证 RV + 库存扣减 + 30 天跟踪） */
export function postReturnApi(id) {
  return request.post(`/proc/returns/${id}/postings`, {})
}

/** 跟踪闭环 */
export function closeTrackApi(id, remark) {
  return request.post(`/proc/returns/${id}/track-close`, { remark })
}
