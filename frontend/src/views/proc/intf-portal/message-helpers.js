/** 15.4 报文状态标签类型（幂等判定/结果回执两页共用的纯展示映射） */
export function stTag(s) {
  return { SUCCESS: 'success', FAILED: 'danger', PENDING: 'warning', DLQ: 'danger', RECEIVED: 'info', PROCESSED: 'success' }[s] || 'info'
}
