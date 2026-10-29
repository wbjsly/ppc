import request from '@/utils/request'

// ---------- 权限复核（add-access-review-console，16.6 SOP-5.6-D） ----------

/** 手动触发季度任务生成（风险分层；同周期幂等 422） */
export function uarGenerate() {
  return request({ url: '/api/system/uar/tasks/generate', method: 'post' })
}

/** 任务列表（含完成度统计） */
export function uarTaskList() {
  return request({ url: '/api/system/uar/tasks', method: 'get' })
}

/** 任务项列表（mineOnly=仅本人待办） */
export function uarItemList(taskId, mineOnly) {
  return request({ url: '/api/system/uar/items', method: 'get', params: { taskId, mineOnly: !!mineOnly } })
}

/** 逐项判定（KEEP/RECLAIM；保留未使用/超基线须理由 ≥10 字） */
export function uarJudge(itemId, data) {
  return request({ url: `/api/system/uar/items/${itemId}/judge`, method: 'post', data })
}

/** 执行收回（台账回收绕基线守卫 / 直删投影；幂等） */
export function uarReclaim(itemId) {
  return request({ url: `/api/system/uar/items/${itemId}/reclaim`, method: 'post' })
}

/** 汇总生成报告（task 唯一幂等） */
export function uarSummarize(taskId) {
  return request({ url: `/api/system/uar/tasks/${taskId}/summarize`, method: 'post' })
}

/** ADMIN 确认（双签第一步） */
export function uarConfirm(taskId) {
  return request({ url: `/api/system/uar/tasks/${taskId}/confirm`, method: 'post' })
}

/** 内审会签归档（ROLE_UAR_AUDITOR，双签第二步） */
export function uarSign(taskId) {
  return request({ url: `/api/system/uar/tasks/${taskId}/sign`, method: 'post' })
}

/** 报告列表 */
export function uarReportList() {
  return request({ url: '/api/system/uar/reports', method: 'get' })
}

/** 逾期升级扫描（BR-5.6-10；定时任务默认关） */
export function uarSweepOverdue() {
  return request({ url: '/api/system/uar/sweep-overdue', method: 'post' })
}
