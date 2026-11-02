/** 16.6 存取复核各页共用的纯展示映射（时间格式化 / 任务状态标签） */

export function fmtTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '—'
}
export function taskType(s) {
  return s === 'ARCHIVED' ? 'success' : s === 'COMPLETED' ? 'warning' : 'info'
}
export function taskLabel(s) {
  return ({ REVIEWING: '复核中', COMPLETED: '待归档', ARCHIVED: '已归档' })[s] || s
}
