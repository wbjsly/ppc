import request from '@/utils/request'

/** 提交审批（聚合可提交态，三档限额路由） */
export function submitApprovalApi(prId) {
  return request.post('/proc/approvals/submit', { prId })
}

export function passApprovalApi(taskId) {
  return request.post('/proc/approvals/pass', { taskId })
}

export function rejectApprovalApi(taskId, reason) {
  return request.post('/proc/approvals/reject', { taskId, reason })
}

/** 待办列表（含 sweep 标记/路由链/辅助信息） */
export function getApprovalTodoApi() {
  return request.get('/proc/approvals/todo')
}

export function getApprovalLogsApi(prId) {
  return request.get(`/proc/approvals/pr/${prId}/logs`)
}
