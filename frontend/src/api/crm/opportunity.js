import request from '@/utils/request'

// ---------- 商机（3.1.1 / 3.1.2 / 3.1.3） ----------

export function getOpportunitiesApi(params) {
  return request.get('/crm/opportunities', { params })
}

export function getOpportunityApi(id) {
  return request.get(`/crm/opportunities/${id}`)
}

/** 漏斗 / 转化率 / 平均销售周期 / 赢率（BR-4.8-09） */
export function getOppStatsApi() {
  return request.get('/crm/opportunities/stats')
}

/** 创建：手工登记 或 线索转化（带 leadId，等级 ≥ B） */
export function createOpportunityApi(data) {
  return request.post('/crm/opportunities', data)
}

export function updateOpportunityApi(data) {
  return request.put('/crm/opportunities', data)
}

/** 阶段推进（提交销售经理审批，通过前停在原阶段） */
export function advanceStageApi(id, data) {
  return request.post(`/crm/opportunities/${id}/advance-stage`, data)
}

/** 丢失归档（原因分类 + 说明必填） */
export function markLostApi(id, data) {
  return request.post(`/crm/opportunities/${id}/lost`, data)
}

export function getStageLogsApi(id) {
  return request.get(`/crm/opportunities/${id}/stage-logs`)
}

export function sweepOpportunitiesApi() {
  return request.post('/crm/opportunities/sweep')
}

// ---------- 跟进记录（仅追加） ----------
export function getOppFollowupsApi(id) {
  return request.get(`/crm/opportunities/${id}/followups`)
}

export function addOppFollowupApi(id, data) {
  return request.post(`/crm/opportunities/${id}/followups`, data)
}

// ---------- 3.1.2 转化闸口（BR-4.3-07） ----------
export function checkQuoteApi(id) {
  return request.post(`/crm/opportunities/${id}/check-quote`)
}

// ---------- 站内通知（超百万通知销售经理） ----------
export function getNoticesApi() {
  return request.get('/crm/notices')
}

export function getUnreadCountApi() {
  return request.get('/crm/notices/unread-count')
}

export function markNoticeReadApi(id) {
  return request.put(`/crm/notices/${id}/read`)
}
