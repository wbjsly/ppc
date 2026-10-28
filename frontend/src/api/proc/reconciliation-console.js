// 12.6 对账协同操作台（add-reconciliation-console）
// 零新端点：全部复用既有 statements 封装（登记/详情/确认在 fin/payment，系统匹配在 proc/portal-collaboration）
export {
  getStatementPageApi,
  getStatementDetailApi,
  createStatementApi,
  confirmStatementApi
} from '@/api/fin/payment'
export { matchStatementApi } from '@/api/proc/portal-collaboration'
// 双签签署走通用审批底座（StatementReconcile 实例，12.6.2 内嵌待签区）
export { getQmsApprovalTodoApi, passQmsApprovalApi } from '@/api/qms/approval'
