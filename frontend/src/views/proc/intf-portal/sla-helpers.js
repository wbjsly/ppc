/** 15.8 SLA 报告状态标签类型与文案（月度生成/归档发布两页共用的纯展示映射） */
export function stTag(s) {
  return { PUBLISHED: 'success', ARCHIVED: 'success', DRAFT: 'info', REVIEWING: 'warning' }[s] || 'info'
}

export function stText(s) {
  return { DRAFT: '草稿', REVIEWING: '审核中', ARCHIVED: '已归档', PUBLISHED: '已发布' }[s] || s
}
