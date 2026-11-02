/** 变更单状态 → 展示名/标签类型（日历与回滚两页共用，拆分自原变更管理操作台）。 */
export function stLabel(s) {
  return { SUBMITTED: '待审批', APPROVED: '已审批', EXECUTING: '执行中', ROLLED_BACK: '已回滚', CLOSED: '已关闭' }[s] || s
}

export function stTag(s) {
  return { SUBMITTED: 'info', APPROVED: 'primary', EXECUTING: 'warning', ROLLED_BACK: 'danger', CLOSED: 'success' }[s] || 'info'
}
