/** 15.1 契约状态标签类型（目录登记/契约评审/版本登记三页共用的纯展示映射） */
export function stTag(s) {
  return { PUBLISHED: 'success', DRAFT: 'info', REVIEWING: 'warning', REJECTED: 'danger' }[s] || 'info'
}
