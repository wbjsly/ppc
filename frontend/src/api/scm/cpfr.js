import request from '@/utils/request'

// ---------- CPFR 协同预测与补货（add-cpfr-forecast-replenishment，M12.8） ----------

// ---- 协议（12.8.1 Tab1） ----

/** 协议分页（keyword=供应商名） */
export function agreementPage(params) {
  return request({ url: '/api/scm/cpfr/agreements', method: 'get', params })
}

/** 协议详情（含缺失参数清单） */
export function agreementDetail(id) {
  return request({ url: `/api/scm/cpfr/agreements/${id}`, method: 'get' })
}

/** 创建/更新协议（SCMDIR/ADMIN） */
export function saveAgreement(data) {
  return request({ url: '/api/scm/cpfr/agreements', method: 'post', data })
}

/** 启用协同（BR-4.9-31 参数不全 422） */
export function enableAgreement(id) {
  return request({ url: `/api/scm/cpfr/agreements/${id}/enable`, method: 'post' })
}

/** 停用协同 */
export function disableAgreement(id) {
  return request({ url: `/api/scm/cpfr/agreements/${id}/disable`, method: 'post' })
}

// ---- 预测版本（12.8.1 Tab2） ----

/** 预测版本分页 */
export function forecastPage(params) {
  return request({ url: '/api/scm/cpfr/forecasts', method: 'get', params })
}

/** 建预测版本（DRAFT） */
export function createForecastHead(data) {
  return request({ url: '/api/scm/cpfr/forecasts', method: 'post', data })
}

/** 录/改 SKU×周预测行 */
export function saveForecastLine(data) {
  return request({ url: '/api/scm/cpfr/forecasts/lines', method: 'post', data })
}

/** 预测行清单（含快照） */
export function forecastLines(headId, params) {
  return request({ url: `/api/scm/cpfr/forecasts/${headId}/lines`, method: 'get', params })
}

/** 生成数据包（自动快照库存/在途/安全库存） */
export function generatePackage(headId) {
  return request({ url: `/api/scm/cpfr/forecasts/${headId}/generate-package`, method: 'post' })
}

/** 按周期推送（未启用协议 422） */
export function pushCycle(headId) {
  return request({ url: `/api/scm/cpfr/forecasts/${headId}/push`, method: 'post' })
}

// ---- 反馈监控（12.8.1 Tab3） ----

/** 周期反馈监控（惰性推进 48h/72h/默认采纳） */
export function feedbackMonitor(params) {
  return request({ url: '/api/scm/cpfr/feedbacks/monitor', method: 'get', params })
}

/** 时限判定手动触发 */
export function evaluateDeadlines() {
  return request({ url: '/api/scm/cpfr/deadlines/evaluate', method: 'post' })
}

// ---- 例外协商（12.8.1 Tab4） ----

/** 例外清单（含调整历史） */
export function exceptionList(params) {
  return request({ url: '/api/scm/cpfr/exceptions', method: 'get', params })
}

/** 计划员终确认例外 */
export function finalConfirmException(id, data) {
  return request({ url: `/api/scm/cpfr/exceptions/${id}/final-confirm`, method: 'post', data })
}

// ---- 补货计划（12.8.2） ----

/** 计划清单（含行与 ZONE） */
export function planList(params) {
  return request({ url: '/api/scm/cpfr/plans', method: 'get', params })
}

/** 创建联合补货计划（从已推送预测行生成） */
export function createPlan(data) {
  return request({ url: '/api/scm/cpfr/plans', method: 'post', data })
}

/** 确认联合补货计划（side=SUPPLIER/INTERNAL） */
export function confirmPlan(id, data) {
  return request({ url: `/api/scm/cpfr/plans/${id}/confirm`, method: 'post', data })
}

/** 冻结期/弹性调整（FREEZE/超弹性 → L2 PMGR 审批） */
export function adjustPlanLine(lineId, data) {
  return request({ url: `/api/scm/cpfr/plan-lines/${lineId}/adjust`, method: 'post', data })
}

/** 转化登记（实际到货量） */
export function convertPlanLine(lineId, data) {
  return request({ url: `/api/scm/cpfr/plan-lines/${lineId}/convert`, method: 'post', data })
}

/** 生成 PO 跳转参数（URL 预填） */
export function poJumpParams(lineId) {
  return request({ url: `/api/scm/cpfr/plan-lines/${lineId}/po-jump`, method: 'get' })
}

/** 月度准确率评估 */
export function evaluateAccuracy(yyyyMM) {
  return request({ url: `/api/scm/cpfr/accuracy/${yyyyMM}/evaluate`, method: 'post' })
}

// ---- 门户 CPFR Tab（供应商侧） ----

/** 我的 CPFR 协议 */
export function portalAgreements() {
  return request({ url: '/api/portal/cpfr/agreements', method: 'get' })
}

/** 我的预测数据包（只读含快照） */
export function portalPackages(params) {
  return request({ url: '/api/portal/cpfr/forecast-packages', method: 'get', params })
}

/** 我的反馈 */
export function portalFeedbacks(params) {
  return request({ url: '/api/portal/cpfr/feedbacks', method: 'get', params })
}

/** 提交反馈 */
export function portalSubmitFeedback(data) {
  return request({ url: '/api/portal/cpfr/feedbacks', method: 'post', data })
}

/** 我的待处理例外 */
export function portalExceptions() {
  return request({ url: '/api/portal/cpfr/exceptions', method: 'get' })
}

/** 例外调整意见 */
export function portalOpinion(id, data) {
  return request({ url: `/api/portal/cpfr/exceptions/${id}/opinion`, method: 'post', data })
}

/** 我的联合补货计划 */
export function portalPlans(params) {
  return request({ url: '/api/portal/cpfr/plans', method: 'get', params })
}

/** 确认联合补货计划（供应商方） */
export function portalConfirmPlan(id) {
  return request({ url: `/api/portal/cpfr/plans/${id}/confirm`, method: 'post' })
}
