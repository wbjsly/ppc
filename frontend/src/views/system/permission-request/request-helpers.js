/** 16.3/16.4 权限申请各页共用的纯展示映射（标签类型/文案/时间格式化） */

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
export function riskType(r) {
  return r === 'PRIVILEGED' ? 'danger' : r === 'SENSITIVE' ? 'warning' : 'info'
}
export function riskLabel(r) {
  return r === 'PRIVILEGED' ? '特权' : r === 'SENSITIVE' ? '敏感' : '普通'
}
export function statusType(s) {
  return ({ PENDING_APPROVAL: 'warning', APPROVED_PENDING: 'info', ACTIVE: 'success',
    REJECTED: 'danger', EXPIRED: 'info', CANCELLED: 'info' })[s] || 'info'
}
export function statusLabel(s) {
  return ({ PENDING_APPROVAL: '审批中', APPROVED_PENDING: '待生效', ACTIVE: '已生效',
    REJECTED: '已驳回', EXPIRED: '已到期', CANCELLED: '已取消' })[s] || s
}
export function ledStatusType(s) {
  return s === 'ACTIVE' ? 'success' : s === 'RECLAIMED' ? 'info' : 'warning'
}
export function ledStatusLabel(s) {
  return s === 'ACTIVE' ? '生效中' : s === 'RECLAIMED' ? '已回收' : '已过期'
}
export function taskType(s) {
  return s === 'PASSED' ? 'success' : s === 'ACTIVE' ? 'warning'
    : s === 'REJECTED' ? 'danger' : 'info'
}
export function taskLabel(s) {
  return ({ ACTIVE: '待签', PASSED: '已签', REJECTED: '驳回', PENDING: '未到',
    SKIPPED: '跳过' })[s] || s
}
