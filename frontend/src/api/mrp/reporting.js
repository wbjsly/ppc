import request from '@/utils/request'

/**
 * 报工管理（5.7，change add-reporting-management，spec shop-floor-reporting /
 * in-process-quality / completion-backflush）。
 * 5.7.1 报工录入（SHOP_FLOOR 作业台）/ 5.7.2 报工管理（PLANNER/PLAN_MGR 管理台）。
 */

// ---------- 5.7.1 报工录入（录入类：SHOP_FLOOR/ADMIN） ----------

/** 开工并创建报工单（FR-4.5-5-1：终态阻断 422、超期黄标、人单代报留痕） */
export function openReportingApi(dispatchId, mode) {
  return request.post('/mrp/reporting/open', { dispatchId, mode })
}

/** 提交报工（OPEN→CONFIRMED；首件/IPQC/超产/工时异常/冻结前置校验） */
export function confirmReportingApi(id) {
  return request.post(`/mrp/reporting/confirm/${id}`)
}

/** 放弃报工草稿（仅 OPEN；CONFIRMED 422「仅可通过冲销纠正」） */
export function discardReportingApi(id) {
  return request.post(`/mrp/reporting/discard/${id}`)
}

/** 投料行保存并即扣（AVAILABLE_QTY −= qty，余量护栏 422） */
export function addFeedApi(payload) {
  return request.post('/mrp/reporting/feed', payload)
}

/** 投料行列表 */
export function getFeedsApi(rptId) {
  return request.get(`/mrp/reporting/feeds/${rptId}`)
}

/** 产出记录与超产分级（阈值内 overprodAck 二次确认；超阈值挂 OverProd 审批） */
export function saveOutputApi(payload) {
  return request.post('/mrp/reporting/output', payload)
}

/** 产出行 */
export function getOutputApi(rptId) {
  return request.get(`/mrp/reporting/output/${rptId}`)
}

/** 工时分项记录（准备/加工/设备；偏离标准 > WORKHOUR_DEVIATION_ALERT 生成主管待办） */
export function saveHoursApi(payload) {
  return request.post('/mrp/reporting/hours', payload)
}

// ---------- 5.7.2 报工管理（管理类：PLANNER/PLAN_MGR/ADMIN） ----------

/** 报工单详情 */
export function getReportingDetailApi(id) {
  return request.get(`/mrp/reporting/detail/${id}`)
}

/** 报工记录查询（工单/工序/状态筛选，创建时间倒序） */
export function getReportingListApi(moId, opSeq, status) {
  return request.get('/mrp/reporting/list', { params: { moId, opSeq, status } })
}

/** 工序累计（良率口径：goodQty/ngQty/inputQty/rptCount，REVERSED 剔除） */
export function getTotalsApi(moId, opSeq) {
  return request.get('/mrp/reporting/totals', { params: { moId, opSeq } })
}

/** 工序良率与超阈值判定（actualYield/standardYield/deviation/exceeded） */
export function getYieldApi(moId, opSeq) {
  return request.get('/mrp/reporting/yield', { params: { moId, opSeq } })
}

/** 工时异常主管确认（PLAN_MGR/ADMIN；确认后放行 submit） */
export function confirmHoursApi(rptId) {
  return request.post(`/mrp/reporting/hours/confirm/${rptId}`)
}

/** 冲销申请（原因必填；ReportReversal 审批 PLAN_MGR 签，通过执行五步反向） */
export function applyReversalApi(rptId, reason) {
  return request.post('/mrp/reporting/reversal', { rptId, reason })
}

/** 完工确认接管（校验全工序 DONE+检验无未决+无冻结 → 倒冲补足 → Completed） */
export function completeMoApi(moId) {
  return request.post(`/mrp/reporting/complete/${moId}`)
}
