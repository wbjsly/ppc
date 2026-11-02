/** 1.8 事件同步两页共用的时间格式化（拆分自原事件同步操作台）。 */
export function formatTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : ''
}
