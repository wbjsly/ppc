import request from '@/utils/request'

// ============================================================
// M12.5 订单协同操作台（add-po-collaboration-console）
// 复用 portal-collaboration 既有端点封装（收口仅动页面，后端端点保留）；
// 新增：工单分页/工单解锁（L5 留痕）/发起协商/推送台账。
// ============================================================

export {
  getPoCoopPageApi,
  getPoCoopTimelineApi,
  remindPoApi,
  offlineConfirmPoApi,
  acceptPoChangeApi,
  unlockPoSupplierApi
} from './portal-collaboration'

/** 协同异常工单分页（status=OPEN/DONE） */
export function getPoTicketsApi(params) {
  return request.get('/proc/po-coops/tickets', { params })
}

/** 工单处理解锁（DONE + 解锁人/时间/原因 L5 留痕，ADMIN） */
export function resolvePoTicketApi(id, reason) {
  return request.post(`/proc/po-coops/tickets/${id}/resolve`, { reason })
}

/** 发起协商（ADMIN/PM，NEGOTIATE 留痕时间线可见） */
export function negotiatePoApi(poId, data) {
  return request.post(`/proc/po-coops/${poId}/negotiate`, data || {})
}

/** 推送台账（outbox PROC.PO_PUSHED 明细，12.5.1） */
export function getPushLedgerApi(params) {
  return request.get('/proc/po-coops/push-ledger', { params })
}
