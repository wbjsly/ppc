import request from '@/utils/request'

// ---------- 服务工单（add-service-order，M11.10） ----------

// ---- 投诉受理（11.10.1） ----

/** 投诉登记（必填 L1、自动定级建议、重复投诉候选） */
export function registerOrder(data) {
  return request({ url: '/api/pm/service-orders', method: 'post', data })
}

/** 工单分页（客户/等级/状态/工程师/关键字） */
export function orderPage(params) {
  return request({ url: '/api/pm/service-orders', method: 'get', params })
}

/** 负载推荐服务工程师（在手工单最少优先） */
export function recommendEngineer() {
  return request({ url: '/api/pm/service-orders/engineers/recommend', method: 'get' })
}

/** 关联为重复投诉（duplicate_of，SLA 独立计时） */
export function markDuplicate(id, data) {
  return request({ url: `/api/pm/service-orders/${id}/duplicate`, method: 'put', data })
}

/** 人工调整定级（理由必填，L5 留痕） */
export function adjustLevel(id, data) {
  return request({ url: `/api/pm/service-orders/${id}/level`, method: 'put', data })
}

/** 派单/改派（负载推荐可覆盖；空=待指派升级主管） */
export function dispatchOrder(id, data) {
  return request({ url: `/api/pm/service-orders/${id}/dispatch`, method: 'put', data })
}

// ---- 工单闭环（11.10.2） ----

/** 工单详情（头+处理记录+回访+确认 token+改进任务） */
export function orderDetail(id) {
  return request({ url: `/api/pm/service-orders/${id}`, method: 'get' })
}

/** 工程师受理（响应计时终点） */
export function acceptOrder(id) {
  return request({ url: `/api/pm/service-orders/${id}/accept`, method: 'post' })
}

/** 追加处理记录（BR-4.8-35 仅追加） */
export function appendRecord(id, data) {
  return request({ url: `/api/pm/service-orders/${id}/records`, method: 'post', data })
}

/** 保存根因分类（未填不可提交关闭；设计缺陷抄送研发） */
export function saveRootCause(id, data) {
  return request({ url: `/api/pm/service-orders/${id}/root-cause`, method: 'put', data })
}

/** 关联 NCR（存在性校验；质量类必关联，BR-4.8-36） */
export function linkNcr(id, data) {
  return request({ url: `/api/pm/service-orders/${id}/ncr`, method: 'put', data })
}

/** 标记已根治（质量类无 NCR → 422） */
export function markCured(id) {
  return request({ url: `/api/pm/service-orders/${id}/cured`, method: 'post' })
}

/** 挂起「待客户信息」（SLA 暂停计时） */
export function suspendOrder(id, data) {
  return request({ url: `/api/pm/service-orders/${id}/suspend`, method: 'post', data })
}

/** 恢复处理（累计挂起时长不计 SLA） */
export function resumeOrder(id) {
  return request({ url: `/api/pm/service-orders/${id}/resume`, method: 'post' })
}

/** 提交客户确认（根因未填 422） */
export function submitConfirm(id) {
  return request({ url: `/api/pm/service-orders/${id}/submit-confirm`, method: 'post' })
}

/** 代录确认/驳回（confirmed_via=AGENT；驳回重计时） */
export function agentConfirm(id, data) {
  return request({ url: `/api/pm/service-orders/${id}/agent-confirm`, method: 'post', data })
}

/** 生成客户确认免密链接（token 一次性） */
export function generateConfirmLink(id) {
  return request({ url: `/api/pm/service-orders/${id}/confirm-link`, method: 'post' })
}

/** 满意度回访（≤2 分自动生成改进任务） */
export function saveSurvey(id, data) {
  return request({ url: `/api/pm/service-orders/${id}/survey`, method: 'put', data })
}

/** 回访统计三维汇总（客户/工程师/类型） */
export function surveyStats(params) {
  return request({ url: '/api/pm/service-orders/surveys/stats', method: 'get', params })
}

/** 改进任务列表（BR-4.8-06） */
export function improveTaskList(params) {
  return request({ url: '/api/pm/service-orders/improve-tasks', method: 'get', params })
}

/** 改进任务状态流转（PENDING→PROCESSING→CLOSED） */
export function updateImproveTask(id, data) {
  return request({ url: `/api/pm/service-orders/improve-tasks/${id}`, method: 'put', data })
}

// ---- 售后领料（复用领料端点，issueType=AFTER_SALE） ----

/** 售后领料创建（DRAFT；serviceOrderNo 必填） */
export function createAfterSaleIssue(data) {
  return request({ url: '/api/inv/issues', method: 'post', data: { ...data, issueType: 'AFTER_SALE' } })
}

/** 售后领料过账（NORMAL 仓扣减 + 成本累加工单） */
export function postIssue(id) {
  return request({ url: `/api/inv/issues/${id}/postings`, method: 'post' })
}

/** 按工单号反查售后领料单 */
export function issueByServiceOrder(serviceOrderNo) {
  return request({ url: '/api/inv/issues', method: 'get', params: { issueType: 'AFTER_SALE', serviceOrderNo } })
}
