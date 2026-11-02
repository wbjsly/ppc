/** 8.5 汇兑重估两页共用的纯展示辅助（金额格式化） */

export const fmt = (v) => {
  const n = Number(v || 0)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
