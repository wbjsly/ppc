import request from '@/utils/request'

// ---------- 报价审批台（add-quote-approval-console，11.2.2；底座 /qms/approvals） ----------

/** 当前用户审批待办（含全部业务类型，页面端过滤 Quote） */
export function approvalTodoApi() {
  return request.get('/qms/approvals/todo')
}

/** 节点通过 */
export function approvalPassApi(data) {
  return request.post('/qms/approvals/pass', data)
}

/** 节点驳回（意见 ≥2 字，整单驳回） */
export function approvalRejectApi(data) {
  return request.post('/qms/approvals/reject', data)
}

/** 按业务类型列实例（已审历史页签，status 可选） */
export function approvalHistoryApi(params) {
  return request.get('/qms/approvals/history', { params })
}
