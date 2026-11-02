/** 8.6 折旧监控两页共用的纯展示辅助（金额/百分比格式化） */

export const fmt = (v) => {
  const n = Number(v || 0)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

export const pct = (v) => (Number(v || 0) * 100).toFixed(2) + '%'
