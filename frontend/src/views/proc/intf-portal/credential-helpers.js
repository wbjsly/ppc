/** 15.3 凭证状态标签类型（签发/轮换/吊销三页共用的纯展示映射） */
export function stTag(s) {
  return { ACTIVE: 'success', PENDING: 'warning', REVOKED: 'danger', EXPIRED: 'info', DEPRECATED: 'info' }[s] || 'info'
}
