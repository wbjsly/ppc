import request from '@/utils/request'

/**
 * 派工管理（5.6，change add-dispatch-management，spec dispatch-management / capacity-load / outsourcing-dispatch）。
 * 5.6.1 智能派工 / 5.6.2 负荷监控 / 5.6.3 外协建议。
 */

/** 待派工列表（Released × 未派工序；priority+planStartDate 排序，D6） */
export function getPendingApi(keyword) {
  return request.get('/mrp/dispatch/pending', { params: { keyword } })
}

/** 已生成派工单列表（确认/取消/重排的操作对象；状态+关键字筛选） */
export function getDispatchListApi(status, keyword) {
  return request.get('/mrp/dispatch/list', { params: { status, keyword } })
}

/** 生成派工单（单/批量同入口；齐套 L1 门禁 422 消息含缺料清单） */
export function generateDispatchApi(rows) {
  return request.post('/mrp/dispatch/generate', { rows })
}

/** 确认派工单（SHOP_FLOOR/PLAN_MGR/ADMIN；触发预扣） */
export function confirmDispatchApi(id) {
  return request.post(`/mrp/dispatch/confirm/${id}`)
}

/** 取消派工单（原因必填；工序回 PENDING；末张取消释放预扣） */
export function cancelDispatchApi(id, reason) {
  return request.put(`/mrp/dispatch/cancel/${id}`, { reason })
}

/** 重新排程（已确认固定；未确认按优先级重装箱，低优先级后移） */
export function rescheduleApi(body) {
  return request.post('/mrp/dispatch/reschedule', body)
}

/** 缺料派工申请（C-4.5-12 L2：计划主管签；同一工序 PENDING 拒重 422） */
export function applyShortageApi(moId, opSeq) {
  return request.post('/mrp/dispatch/shortage-apply', { moId, opSeq })
}

/** 外协改派申请（BR-4.5-21 L2：目标须外协 WC；同一派工单 PENDING 拒重） */
export function applyOutsourceApi(dispatchId, targetWcCode) {
  return request.post('/mrp/dispatch/outsource-apply', { dispatchId, targetWcCode })
}

/** 外协候选推荐（定额矩阵筛外协 WC + 今日负荷 + 协议价参考 D8 仅展示；dispatchId 或 opCode 二选一） */
export function getOutsourceCandidatesApi({ opCode, productCode, dispatchId } = {}) {
  return request.get('/mrp/dispatch/outsource-candidates', {
    params: { opCode, productCode, dispatchId }
  })
}

/** WC×日期负荷（5.6.2 监控表 / 5.6.3 超载清单共用读口径） */
export function getLoadsApi(from, to, wcCode) {
  return request.get('/mrp/capacity/loads', { params: { from, to, wcCode } })
}
