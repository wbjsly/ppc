/** 8.7 银行对账两页共用的纯展示辅助（金额格式化） */

export function fmt(v) {
  const n = Number(v)
  return isNaN(n) ? '0.00' : n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
