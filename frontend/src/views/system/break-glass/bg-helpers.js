/** 16.5 Break-Glass 各页共用的纯展示映射（标签类型/文案/时间格式化） */

export function fmtRoles(json) {
  try {
    return (JSON.parse(json || '[]')).join('、')
  } catch (e) {
    return json
  }
}
export function fmtTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '—'
}
export function stType(s) {
  return ({ PENDING_APPROVAL: 'warning', ACTIVE: 'success', RECLAIMED: 'info',
    REJECTED: 'danger', AUDITED: 'success' })[s] || 'info'
}
export function stLabel(s) {
  return ({ PENDING_APPROVAL: '审批中', ACTIVE: '已生效', RECLAIMED: '已回收',
    REJECTED: '已驳回', AUDITED: '已审计' })[s] || s
}
export function incLabel(s) {
  return ({ PROCESSING: '处理中', RESOLVED: '已解决', CLOSED: '已关闭' })[s] || s
}
export function taskType(s) {
  return s === 'PASSED' ? 'success' : s === 'ACTIVE' ? 'warning'
    : s === 'REJECTED' ? 'danger' : 'info'
}
export function taskLabel(s) {
  return ({ ACTIVE: '待签', PASSED: '已签', REJECTED: '驳回', PENDING: '未到',
    SKIPPED: '跳过' })[s] || s
}
