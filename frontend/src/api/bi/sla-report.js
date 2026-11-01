import request from '@/utils/request'

// ---- 指标与报告（BI_DESIGNER/ADMIN） ----

export function getMetricsApi(period) {
  return request({ url: '/api/bi/sla-reports/metrics', method: 'get', params: { period } })
}

export function collectApi(period) {
  return request({ url: '/api/bi/sla-reports/collect', method: 'post', params: { period } })
}

export function getReportsApi() {
  return request({ url: '/api/bi/sla-reports/reports', method: 'get' })
}

export function getReportApi(period) {
  return request({ url: `/api/bi/sla-reports/reports/${period}`, method: 'get' })
}

export function generateApi(period) {
  return request({ url: `/api/bi/sla-reports/reports/${period}/generate`, method: 'post' })
}

export function saveRootCauseApi(period, items) {
  return request({ url: `/api/bi/sla-reports/reports/${period}/root-cause`, method: 'put', data: items })
}

export function saveImproveApi(period, improve) {
  return request({ url: `/api/bi/sla-reports/reports/${period}/improve`, method: 'put', data: improve })
}

export function submitApi(period) {
  return request({ url: `/api/bi/sla-reports/reports/${period}/submit`, method: 'post' })
}

export function newVersionApi(period) {
  return request({ url: `/api/bi/sla-reports/reports/${period}/new-version`, method: 'post' })
}

export function publishApi(period) {
  return request({ url: `/api/bi/sla-reports/reports/${period}/publish`, method: 'post' })
}

export function getPdfApi(period) {
  return request({ url: `/api/bi/sla-reports/reports/${period}/pdf`, method: 'get' })
}
