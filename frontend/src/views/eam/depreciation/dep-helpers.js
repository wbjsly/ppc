/** 7.2 折旧管理三页共用的金额格式化（拆分自原折旧管理操作台）。 */
export function fmt(v) {
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2 })
}
