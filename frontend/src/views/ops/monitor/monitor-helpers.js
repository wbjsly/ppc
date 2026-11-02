/** 时间格式化（延迟采集与阈值告警两页共用，拆分自原监控处置操作台）。 */
export function fmt(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : ''
}
