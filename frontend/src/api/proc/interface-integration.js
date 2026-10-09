import request from '@/utils/request'

// ============ 运行总览（spec interface-console 8.2） ============

/** KPI：今日调用量/成功率/P95/限流次数/熔断态/告警/死信（台账实时聚合，无数据显示 0） */
export function getKpiApi() {
  return request.get('/intf/console/kpi')
}

/** 通道列表（限流档位 / 熔断状态 / 证书台账） */
export function getChannelsApi() {
  return request.get('/intf/channels')
}

/** 解除熔断（高危：仅 ADMIN） */
export function resetCircuitApi(id) {
  return request.post(`/intf/channels/${id}/circuit-reset`, {})
}

/** 限流档位调整（高危：仅 ADMIN） */
export function setRateTierApi(id, tier) {
  return request.post(`/intf/channels/${id}/rate-tier`, { tier })
}

/** 调用审计日志 */
export function getCallLogsApi(params) {
  return request.get('/intf/call-logs', { params })
}

/** 模拟调用方（OK / BAD_SIGNATURE / EXPIRED_TIMESTAMP / STORM / RATE_STORM） */
export function runSimulationApi(data) {
  return request.post('/intf/simulations', data)
}

// ============ 事件与报文（spec interface-event-delivery / edi-message-processing） ============

export function getDeliveriesApi(params) {
  return request.get('/intf/deliveries', { params })
}

export function getDeliveryStatsApi() {
  return request.get('/intf/deliveries/stats')
}

export function scanDeliveriesApi() {
  return request.post('/intf/deliveries/scan', {})
}

export function replayDeliveryApi(id) {
  return request.post(`/intf/deliveries/${id}/replay`, {})
}

export function abandonDeliveryApi(id, note) {
  return request.post(`/intf/deliveries/${id}/abandon`, { note })
}

export function getMessagesApi(params) {
  return request.get('/intf/messages', { params })
}

export function getMessageDetailApi(id) {
  return request.get(`/intf/messages/${id}`)
}

/** 页面上传报文（入口②） */
export function uploadMessageApi(data) {
  return request.post('/intf/messages/upload', data)
}

/** 一键模拟伙伴推送（入口③，真实签名经开放入口） */
export function simulatePushApi(data) {
  return request.post('/intf/messages/simulate-push', data)
}

export function replayMessageApi(id) {
  return request.post(`/intf/messages/${id}/replay`, {})
}

export function getMapRulesApi(params) {
  return request.get('/intf/messages/map-rules', { params })
}

export function saveMapRuleApi(data) {
  return request.post('/intf/messages/map-rules', data)
}

export function getTicketsApi(status) {
  return request.get('/intf/tickets', { params: { status } })
}

export function handleTicketApi(id, data) {
  return request.post(`/intf/deliveries/tickets/${id}/handle`, data || {})
}

// ============ 接入治理（spec interface-onboarding） ============

export function getContractsApi(params) {
  return request.get('/intf/onboarding/contracts', { params })
}

export function createContractApi(data) {
  return request.post('/intf/onboarding/contracts', data)
}

export function submitReviewApi(id) {
  return request.post(`/intf/onboarding/contracts/${id}/submit-review`, {})
}

export function reviewContractApi(id, data) {
  return request.post(`/intf/onboarding/contracts/${id}/review`, data)
}

export function changeVersionApi(id, data) {
  return request.post(`/intf/onboarding/contracts/${id}/version`, data)
}

export function getPassRateApi(id) {
  return request.get(`/intf/onboarding/contracts/${id}/pass-rate`)
}

export function addSandboxCaseApi(data) {
  return request.post('/intf/onboarding/sandbox-cases', data)
}

export function getSandboxCasesApi(contractId) {
  return request.get('/intf/onboarding/sandbox-cases', { params: { contractId } })
}

export function requestReleaseApi(data) {
  return request.post('/intf/onboarding/releases', data)
}

export function getReleasesApi(params) {
  return request.get('/intf/onboarding/releases', { params })
}

export function reviewReleaseApi(id) {
  return request.post(`/intf/onboarding/releases/${id}/review`, {})
}

export function observeCompleteApi(id) {
  return request.post(`/intf/onboarding/releases/${id}/observe-complete`, {})
}

export function archiveReleaseApi(id) {
  return request.post(`/intf/onboarding/releases/${id}/archive`, {})
}

export function getObservationDailyApi(id) {
  return request.get(`/intf/onboarding/releases/${id}/daily`)
}

// ============ 凭证与防护（spec api-credential-management） ============

export function getCredentialsApi(params) {
  return request.get('/intf/credentials', { params })
}

export function issueCredentialApi(data) {
  return request.post('/intf/credentials', data)
}

export function createCredentialLinkApi(id) {
  return request.post(`/intf/credentials/${id}/link`, {})
}

export function rotateCredentialApi(id) {
  return request.post(`/intf/credentials/${id}/rotate`, {})
}

export function revokeCredentialApi(id, reason) {
  return request.post(`/intf/credentials/${id}/revoke`, { reason })
}

export function getCredentialTraceApi(partnerCode) {
  return request.get('/intf/credentials/trace', { params: { partnerCode } })
}

export function sweepCredentialsApi() {
  return request.post('/intf/credentials/sweep', {})
}

// ============ SLA 监控（spec interface-sla-monitoring） ============

export function collectSlaApi() {
  return request.post('/intf/sla/collect', {})
}

export function getMetricsApi(params) {
  return request.get('/intf/sla/metrics', { params })
}

export function getAlertsApi(params) {
  return request.get('/intf/sla/alerts', { params })
}

export function respondAlertApi(id) {
  return request.post(`/intf/sla/alerts/${id}/respond`, {})
}

export function getReportsApi(params) {
  return request.get('/intf/sla/reports', { params })
}

export function generateReportApi(data) {
  return request.post('/intf/sla/reports/generate', data || {})
}

export function reviewReportApi(id, opinion) {
  return request.post(`/intf/sla/reports/${id}/review`, { opinion })
}

export function archiveReportApi(id) {
  return request.post(`/intf/sla/reports/${id}/archive`, {})
}

export function publishReportApi(id, scope) {
  return request.post(`/intf/sla/reports/${id}/publish`, { scope })
}
