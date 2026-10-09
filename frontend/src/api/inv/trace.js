import request from '@/utils/request'

/** 追溯发起（三索引：BATCH/SERIAL/SUPPLIER_BATCH，质量三角色，spec trace-recall） */
export function analyzeTraceApi(data) {
  return request.post('/inv/traces/analyze', data)
}

/** 追溯单分页（状态/关键字） */
export function getTracesApi(params) {
  return request.get('/inv/traces', { params })
}

/** 追溯单详情（五类流向分组 + 报告） */
export function getTraceApi(id) {
  return request.get(`/inv/traces/${id}`)
}

/** 审计时间轴（按单回放，仅认证） */
export function getTraceLogsApi(id) {
  return request.get(`/inv/traces/${id}/logs`)
}

/** 批量冻结执行（复用 4.9 质量冻结链 + 释放预留；前端先 estimate 后执） */
export function freezeTraceApi(id) {
  return request.post(`/inv/traces/${id}/freeze`)
}

/** 在途拦截登记（success=false 自动升级召回行） */
export function interceptTraceApi(flowId, data) {
  return request.post(`/inv/traces/flows/${flowId}/intercept`, data)
}

/** 召回登记（实退 actualQty / 拒退 rejectReason，应召量定格） */
export function returnTraceApi(flowId, data) {
  return request.post(`/inv/traces/flows/${flowId}/return`, data)
}

/** 召回受限区入库 RECALL_IN（WAREHOUSE/ADMIN，仅 RETURN/SCRAP 仓位） */
export function receiveTraceApi(data) {
  return request.post('/inv/traces/receive', data)
}

/** 结案报告（召回率，限 QUALITY_MGR/ADMIN） */
export function closeTraceApi(id) {
  return request.post(`/inv/traces/${id}/close`)
}

/** 受限区仓位（RETURN/SCRAP 下拉，来源仓位主数据 binType 过滤） */
export function getRestrictedBinsApi(whCode) {
  return request.get('/inv/bins', { params: { whCode } })
}
