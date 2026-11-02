/** 8.8 费用报销各页共用的纯展示辅助（金额格式化/日期/状态文案） */

export function today() { return new Date().toISOString().slice(0, 10) }

export function fmt(v) {
  const n = Number(v)
  return isNaN(n) ? '0.00' : n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

export function reimbName(s) { return { PENDING: '审批中', APPROVED: '已审批', REJECTED: '已驳回' }[s] || s }
