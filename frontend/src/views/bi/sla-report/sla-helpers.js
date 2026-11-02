/** 14.9 SLA 报告各页共用的纯展示映射（状态文案/标签类型） */

export function stText(s) {
  return { DRAFT: '草稿', PENDING_APPROVAL: '审核中', APPROVED: '已批准', ARCHIVED: '已归档', PUBLISHED: '已发布' }[s] || s
}

export function stTag(s) {
  return { DRAFT: 'info', PENDING_APPROVAL: 'warning', APPROVED: 'warning', ARCHIVED: 'success', PUBLISHED: 'success' }[s] || 'info'
}
