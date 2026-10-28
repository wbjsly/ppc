import request from '@/utils/request'

// ============ 12.3 接口调用操作台（add-interface-call-console，全只读） ============

/** 认证拒绝原因聚合 + 明细下钻（400/401/403，四类可观测原因） */
export function getAuthRejectionsApi(params) {
  return request.get('/intf/call-console/auth-rejections', { params })
}

/** 限流状态总览：通道档位/桶容量/熔断 + 最新统计窗口 */
export function getRateOverviewApi() {
  return request.get('/intf/call-console/rate-overview')
}

/** 档位变更历史（自动降档 + 手动调档同源留痕） */
export function getTierChangesApi(params) {
  return request.get('/intf/call-console/tier-changes', { params })
}

/** 429 触发趋势（默认 24h、上限 31 天，total 与明细可对账） */
export function getRateTrendApi(params) {
  return request.get('/intf/call-console/rate-trend', { params })
}

/** Trace 下钻：按 X-Request-Id（含被拒请求） */
export function getTraceApi(reqId) {
  return request.get(`/intf/call-console/trace/${encodeURIComponent(reqId)}`)
}

/** 审计聚合：调用方×接口×日（调用量/成功率/P95/4xx/5xx） */
export function getAuditAggregatesApi(params) {
  return request.get('/intf/call-console/audit-aggregates', { params })
}

// 凭证只读清单复用既有端点（写操作在 12.1.3 / 2.8.3）
export { getCredentialsApi } from './interface-integration'
// 审计明细复用既有端点（respGroup=2xx/4xx/5xx 为本 change 只读扩展）
export { getCallLogsApi } from './interface-integration'
