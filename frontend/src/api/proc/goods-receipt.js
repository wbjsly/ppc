import request from '@/utils/request'

// 收货管理 API（2.4 全组，change add-goods-receipt）

// ---------- 2.4.1 登记 ----------

/** 可收货 PO 候选（APPROVED） */
export function getPoCandidatesApi() {
  return request.get('/proc/grs/po-candidates')
}

/** PO 未清行（预填 qty − receivedQty） */
export function getPoLinesApi(poId) {
  return request.get('/proc/grs/po-lines', { params: { poId } })
}

/** 按 PO 登记（容差分流 + 差异单生成） */
export function createGrByPoApi(payload) {
  return request.post('/proc/grs', payload)
}

/** 无 PO 登记（FREE 免容差） */
export function createGrFreeApi(payload) {
  return request.post('/proc/grs/free', payload)
}

/** 收货单分页 */
export function getGrPageApi(params) {
  return request.get('/proc/grs', { params })
}

/** 收货单详情（行 + 差异） */
export function getGrDetailApi(id) {
  return request.get(`/proc/grs/${id}`)
}

/** 作废（原因必填） */
export function cancelGrApi(id, reason) {
  return request.post(`/proc/grs/${id}/cancel`, { reason })
}

// ---------- 2.4.3 差异与调整单 ----------

/** 差异对账台分页 */
export function getDifferencePageApi(params) {
  return request.get('/proc/gr-differences', { params })
}

/** 差异处置：START_ADJUST / REJECT / CLOSE */
export function disposeDifferenceApi(id, action, note) {
  return request.post(`/proc/gr-differences/${id}/dispose`, { action, note })
}

/** 调整单分页 */
export function getAdjustmentPageApi(params) {
  return request.get('/proc/gr-adjustments', { params })
}

/** 发起调整单（diffId 或 poLineId+addQty，BR-4.2-49） */
export function createAdjustmentApi(payload) {
  return request.post('/proc/gr-adjustments', payload)
}

/** 调整单审批（批准即执行） */
export function approveAdjustmentApi(id, approved, note) {
  return request.post(`/proc/gr-adjustments/${id}/approval`, { approved, note })
}

// ---------- 2.4.2 待检 ----------

/**
 * 待检看板（2.4.2）：2.5 接入后数据源为**检验批**（D1：起算点=登记提交时间）。
 * 人工兜底放行接口已随 2.5 接入移除（qc-hold-area REMOVED），放行动作在检验页完成。
 */
export function getQcHoldApi(params) {
  return request.get('/proc/grs/qc-hold', { params })
}

/** 风险等级选项（A/B/C） */
export function getRiskGradeOptionsApi() {
  return request.get('/proc/grs/risk-grade-options')
}

// ---------- 2.4.4 过账 ----------

/** 入库过账（单事务五步，BR-4.2-28 阻断） */
export function postingGrApi(id) {
  return request.post(`/proc/grs/${id}/postings`)
}
