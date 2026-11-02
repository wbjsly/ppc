import request from '@/utils/request'

// ---------- 13.6 设备监控 ----------
export function deviceRegisterApi(data) {
  return request.post('/iot/devices/register', data)
}
export function deviceConnectTestApi(deviceNo) {
  return request.post(`/iot/devices/${deviceNo}/connect-test`)
}
export function deviceHeartbeatApi(deviceNo) {
  return request.post(`/iot/devices/${deviceNo}/heartbeat`)
}
export function deviceListApi(params) {
  return request.get('/iot/devices', { params })
}
export function deviceDetailApi(deviceNo) {
  return request.get(`/iot/devices/${deviceNo}`)
}
export function deviceStatusDistApi() {
  return request.get('/iot/devices/status-distribution')
}
export function deviceAlertRuleApi(deviceNo, alertRuleJson) {
  return request.put(`/iot/devices/${deviceNo}/alert-rule`, { alertRuleJson })
}

export function telemetryQueryApi(params) {
  return request.get('/iot/telemetry', { params })
}
export function telemetrySuspectApi(params) {
  return request.get('/iot/telemetry/suspect', { params })
}
export function telemetryLatestApi(deviceNo, pointCode) {
  return request.get('/iot/telemetry/latest', { params: { deviceNo, pointCode } })
}
export function telemetryInjectApi(data) {
  return request.post('/iot/telemetry/inject', data)
}
export function telemetryMaintainApi() {
  return request.post('/iot/telemetry/maintain')
}

export function alarmEvaluateApi(data) {
  return request.post('/iot/alarms/evaluate', data)
}
export function alarmListApi(params) {
  return request.get('/iot/alarms', { params })
}
export function alarmConfirmApi(eventNo, data) {
  return request.post(`/iot/alarms/${eventNo}/confirm`, data || {})
}
export function alarmProcessingApi(eventNo, data) {
  return request.post(`/iot/alarms/${eventNo}/processing`, data || {})
}
export function alarmCloseApi(eventNo, data) {
  return request.post(`/iot/alarms/${eventNo}/close`, data || {})
}
export function alarmScanEscalateApi() {
  return request.post('/iot/alarms/scan-escalate')
}

// ---------- 13.8 预测维护 ----------
export function trendCandidatesApi() {
  return request.get('/iot/predictive/trend-candidates')
}
export function predictCreateWoApi(eventNo) {
  return request.post(`/iot/predictive/${eventNo}/create-wo`)
}
export function falsePositiveRateApi() {
  return request.get('/iot/predictive/false-positive-rate')
}
export function markFalsePositiveApi(eventNo, fp) {
  return request.post(`/iot/predictive/${eventNo}/false-positive`, { fp })
}
export function predictWoClosedApi(woNo) {
  return request.post(`/iot/predictive/wo-closed/${woNo}`)
}
