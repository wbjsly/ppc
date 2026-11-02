/** 14.1 报表模板各页共用的纯展示映射（状态文案/标签类型） */

export function statusLabel(s) {
  return { DRAFT: '草稿', TESTING: '测试中', PENDING_APPROVAL: '审批中',
    PUBLISHED: '已发布', REJECTED: '已驳回' }[s] || s
}

export function statusType(s) {
  return { DRAFT: 'info', TESTING: 'warning', PENDING_APPROVAL: 'warning',
    PUBLISHED: 'success', REJECTED: 'danger' }[s] || 'info'
}
