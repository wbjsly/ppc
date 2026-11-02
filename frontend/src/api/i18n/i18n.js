import request from '@/utils/request'

// ---------- 17.1.1 键值维护 ----------
export function getKeysApi(params) {
  return request.get('/i18n/keys', { params })
}
export function saveKeyApi(data) {
  return request.post('/i18n/keys', data)
}
export function deleteKeyApi(id) {
  return request.delete(`/i18n/keys/${id}`)
}
export function importKeysApi(locale, csv) {
  return request.post(`/i18n/keys/import?locale=${locale}`, { csv })
}
export function exportKeysApi(params) {
  return request.get('/i18n/keys/export', { params })
}

// ---------- 17.1.3 版本发布（SOP-5.7-B） ----------
export function buildPackApi(data) {
  return request.post('/i18n/packs/build', data)
}
export function precheckApi(locale) {
  return request.get('/i18n/packs/precheck', { params: { locale } })
}
export function approvePackApi(id) {
  return request.post(`/i18n/packs/${id}/approve`)
}
export function startCanaryApi(id, pct) {
  return request.post(`/i18n/packs/${id}/canary`, null, { params: { pct } })
}
export function checkCanaryApi(id) {
  return request.post(`/i18n/packs/${id}/canary-check`)
}
export function archivePackApi(id) {
  return request.post(`/i18n/packs/${id}/archive`)
}
export function getPacksApi(params) {
  return request.get('/i18n/packs', { params })
}
export function getCurrentPackApi(locale) {
  return request.get(`/i18n/packs/${locale}/current`)
}

// ---------- 17.1.2 缺口报告 ----------
export function getGapsApi(params) {
  return request.get('/i18n/gaps', { params })
}
export function closeGapApi(id) {
  return request.post(`/i18n/gaps/${id}/close`)
}
export function coverageApi(locale) {
  return request.get('/i18n/gaps/coverage', { params: { locale } })
}

// ---------- 17.2 区域配置 ----------
export function getLocalesApi() {
  return request.get('/i18n/locales')
}
export function saveLocaleApi(data) {
  return request.post('/i18n/locales', data)
}
export function validateLocaleApi(locale, fields) {
  return request.post(`/i18n/locales/validate?locale=${locale}`, fields)
}
export function getFallbackEventsApi(limit = 50) {
  return request.get('/i18n/locales/fallback-events', { params: { limit } })
}

// ---------- 17.5 合规字段包 ----------
export function getComplianceApi(params) {
  return request.get('/i18n/compliance', { params })
}
export function saveComplianceApi(data) {
  return request.post('/i18n/compliance', data)
}
export function loadComplianceApi(regPlace) {
  return request.get('/i18n/compliance/load', { params: { regPlace } })
}
export function validateComplianceApi(regPlace, values) {
  return request.post(`/i18n/compliance/validate?regPlace=${regPlace}`, values)
}

// ---------- 17.7 i18n SLA 报告 ----------
export function generateSlaApi(period) {
  return request.post(`/i18n/sla-reports/${period}/generate`)
}
export function getSlaApi(period) {
  return request.get(`/i18n/sla-reports/${period}`)
}
export function archiveSlaApi(period) {
  return request.post(`/i18n/sla-reports/${period}/archive`)
}
export function publishSlaApi(period, scope) {
  return request.post(`/i18n/sla-reports/${period}/publish`, null, { params: { scope } })
}
export function getSlaListApi() {
  return request.get('/i18n/sla-reports')
}
