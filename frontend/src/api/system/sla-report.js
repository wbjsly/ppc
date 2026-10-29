import request from '@/utils/request'

/** 报告列表（period/status 过滤，分页倒序，含指标 JSON） */
export function listReports(params) {
  return request({ url: '/api/system/sla-reports', method: 'get', params })
}

/** 按月生成/重生成（YYYY-MM；已归档 422） */
export function generateReport(period) {
  return request({ url: '/api/system/sla-reports/generate', method: 'post', data: { period } })
}

/** 月度 sweep（上月补缺，任意已存在跳过） */
export function sweepReports() {
  return request({ url: '/api/system/sla-reports/sweep', method: 'post' })
}

/** 归档（DRAFT → ARCHIVED，版本锁定） */
export function archiveReport(id) {
  return request({ url: `/api/system/sla-reports/${id}/archive`, method: 'post' })
}

/** 对外发布（非 ARCHIVED → 422 + 违规留痕） */
export function publishReport(id) {
  return request({ url: `/api/system/sla-reports/${id}/publish`, method: 'post' })
}

/** CSV 下载（blob；仅 ARCHIVED/PUBLISHED） */
export function downloadReport(id) {
  return request({
    url: `/api/system/sla-reports/${id}/download`,
    method: 'get',
    responseType: 'blob'
  })
}
