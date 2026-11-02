import request from '@/utils/request'

// ---- 日报定义（写限 BI_DESIGNER/ADMIN） ----

export function getDefsApi() {
  return request({ url: '/bi/daily-reports/defs', method: 'get' })
}

export function getDefApi(id) {
  return request({ url: `/bi/daily-reports/defs/${id}`, method: 'get' })
}

export function saveDefApi(data) {
  return request({ url: '/bi/daily-reports/defs', method: 'post', data })
}

export function changeDefStatusApi(id, status) {
  return request({ url: `/bi/daily-reports/defs/${id}/status`, method: 'put', params: { id, status } })
}

export function deleteDefApi(id) {
  return request({ url: `/bi/daily-reports/defs/${id}`, method: 'delete' })
}

// ---- 订阅（本人维度） ----

export function mySubsApi() {
  return request({ url: '/bi/daily-reports/subscriptions', method: 'get' })
}

export function saveSubApi(data) {
  return request({ url: '/bi/daily-reports/subscriptions', method: 'post', data })
}

export function updateSubApi(id, channels) {
  return request({ url: `/bi/daily-reports/subscriptions/${id}`, method: 'put', data: channels })
}

export function unsubscribeApi(id) {
  return request({ url: `/bi/daily-reports/subscriptions/${id}/unsubscribe`, method: 'post' })
}

// ---- 推送与触达 ----

export function getPushLogsApi(params) {
  return request({ url: '/bi/daily-reports/push-logs', method: 'get', params })
}

export function getStatsApi() {
  return request({ url: '/bi/daily-reports/stats', method: 'get' })
}

export function markReadApi(id) {
  return request({ url: `/bi/daily-reports/push-logs/${id}/mark-read`, method: 'post' })
}

export function pushNowApi(reportId) {
  return request({ url: '/bi/daily-reports/push-now', method: 'post', params: { reportId } })
}
