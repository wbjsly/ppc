import request from '@/utils/request'

/** 通用审批底座（add-quality-collaboration design D4） */

/** 当前用户待办（可签节点 + 超时升级通知） */
export function getQmsApprovalTodoApi() {
  return request.get('/qms/approvals/todo')
}

/** 节点通过（意见可空默认同意） */
export function passQmsApprovalApi(taskId, opinion) {
  return request.post('/qms/approvals/pass', { taskId, opinion })
}

/** 节点驳回（意见必填 ≥2 字） */
export function rejectQmsApprovalApi(taskId, reason) {
  return request.post('/qms/approvals/reject', { taskId, reason })
}

/** 按业务单据的审批日志 */
export function getQmsApprovalLogsApi(bizType, bizId) {
  return request.get('/qms/approvals/logs', { params: { bizType, bizId } })
}

/** 手动触发超时扫描（72h 提醒 / 7 天升级，幂等） */
export function sweepQmsApprovalApi() {
  return request.post('/qms/approvals/sweep', {})
}
