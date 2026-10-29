import request from '@/utils/request'

// ---------- 紧急权限（add-break-glass-console，16.5 SOP-5.6-C） ----------

/** 故障工单列表（status 过滤；申请侧仅 PROCESSING） */
export function incidentList(params) {
  return request({ url: '/api/system/break-glass/incidents', method: 'get', params })
}

/** 新建故障工单 */
export function incidentSave(data) {
  return request({ url: '/api/system/break-glass/incidents', method: 'post', data })
}

/** 工单状态流转（PROCESSING → RESOLVED/CLOSED） */
export function incidentTransition(id, status) {
  return request({ url: `/api/system/break-glass/incidents/${id}/status`, method: 'put', data: { status } })
}

/** 权限模板列表（含停用） */
export function templateList() {
  return request({ url: '/api/system/break-glass/templates', method: 'get' })
}

/** 新建模板（角色集合禁特权角色 422） */
export function templateSave(data) {
  return request({ url: '/api/system/break-glass/templates', method: 'post', data })
}

/** 提交 Break-Glass 申请 */
export function bgSubmit(data) {
  return request({ url: '/api/system/break-glass/requests', method: 'post', data })
}

/** 申请单列表 */
export function bgRequestList(params) {
  return request({ url: '/api/system/break-glass/requests', method: 'get', params })
}

/** 申请单详情（审批日志） */
export function bgDetail(id) {
  return request({ url: `/api/system/break-glass/requests/${id}`, method: 'get' })
}

/** 提前归还（幂等） */
export function bgReclaim(id) {
  return request({ url: `/api/system/break-glass/requests/${id}/reclaim`, method: 'post' })
}

/** 事后审计报告列表 */
export function auditList() {
  return request({ url: '/api/system/break-glass/audits', method: 'get' })
}

/** 签署审计结论 */
export function signAudit(id, conclusion) {
  return request({ url: `/api/system/break-glass/audits/${id}/sign`, method: 'post', data: { conclusion } })
}

/** 审计逾期告警扫描（定时任务默认关） */
export function sweepAuditOverdue() {
  return request({ url: '/api/system/break-glass/audits/sweep-overdue', method: 'post' })
}

/** 审批签署（复用既有审批端点，详情抽屉就地签署） */
export function approvalPass(taskId, opinion) {
  return request({ url: '/api/qms/approvals/pass', method: 'post', data: { taskId, opinion } })
}

/** 审批驳回（意见 ≥2 字） */
export function approvalReject(taskId, opinion) {
  return request({ url: '/api/qms/approvals/reject', method: 'post', data: { taskId, opinion } })
}

/** 用户列表（申请人选择，沿 16.3 api 口径） */
export function accountPage(params) {
  return request({ url: '/api/system/accounts', method: 'get', params })
}
