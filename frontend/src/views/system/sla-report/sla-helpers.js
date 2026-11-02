/** 16.9 SLA 报告两页共用的纯展示映射（报告状态标签） */

export const statusType = (s) => ({ DRAFT: 'info', ARCHIVED: 'warning', PUBLISHED: 'success' }[s] || 'info')
export const statusText = (s) => ({ DRAFT: '草稿', ARCHIVED: '已归档', PUBLISHED: '已发布' }[s] || s)
