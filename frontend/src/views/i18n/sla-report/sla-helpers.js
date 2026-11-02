/**
 * 17.7 服务报告两页共用的纯展示辅助（label/tag 映射，design D2 helpers 规则）。
 */
export function stTag(s) {
  return { DRAFT: 'info', ARCHIVED: 'warning', PUBLISHED: 'success' }[s] || 'info'
}

export function stText(s) {
  return { DRAFT: '草稿', ARCHIVED: '已归档', PUBLISHED: '已发布' }[s] || s || '—'
}
