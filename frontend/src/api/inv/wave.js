import request from '@/utils/request'
import { getRoutePageApi, getActiveRoutesApi, createRouteApi, updateRouteApi, changeRouteStatusApi } from './route'

// ---------- 4.8.1 波次拣货 ----------

/** 按规则生成波次候选预览（不落库，BR-4.4-42 聚类+超容拆分） */
export function previewWaveApi() {
  return request.post('/inv/waves/preview')
}

/** 确认候选组建波次 */
export function createWaveApi(groups) {
  return request.post('/inv/waves', { groups })
}

export function getWavePageApi(params) {
  return request.get('/inv/waves', { params })
}

export function getWaveDetailApi(id) {
  return request.get(`/inv/waves/${id}`)
}

export function cancelWaveApi(id, reason) {
  return request.post(`/inv/waves/${id}/cancel`, null, { params: { reason } })
}

/** 波次级统一分配（只计算落行，状态仍 CREATED） */
export function allocateWaveApi(id) {
  return request.post(`/inv/waves/${id}/allocate`)
}

/** 确认分配（→ ALLOCATED + 生成 WAVE 合并任务；LOCKED 行 422） */
export function confirmAllocateApi(id) {
  return request.post(`/inv/waves/${id}/allocate-confirm`)
}

/** 人工改批（C-4.4-08：行锁定 + 主管审批） */
export function adjustWaveApi(id, data) {
  return request.post(`/inv/waves/${id}/adjust`, data)
}

// ---------- 4.8.2 集货发运 ----------

/** 分播复核（不平落 WAVE_SORT 差异；全过 → STAGING） */
export function sortConfirmApi(waveId, shipId, lines) {
  return request.post(`/inv/waves/${waveId}/docs/${shipId}/sort`, lines)
}

/** 装车确认（比对分播快照，不一致 422 阻断该单 C-4.4-06） */
export function loadConfirmApi(waveId, shipId, lines) {
  return request.post(`/inv/waves/${waveId}/docs/${shipId}/load`, lines)
}

/** 发运确认（逐单独立过账 BR-4.4-46） */
export function shipConfirmApi(waveId) {
  return request.post(`/inv/waves/${waveId}/ship`)
}

/** 失败订单单独重试 */
export function retryShipApi(waveId, shipId) {
  return request.post(`/inv/waves/${waveId}/docs/${shipId}/retry`)
}

// ---------- 配送线路（4.8.1 页内 Tab） ----------
export { getRoutePageApi, getActiveRoutesApi, createRouteApi, updateRouteApi, changeRouteStatusApi }

/** 差异闭环（4.7.4 通用，分播差异同口径） */
export function closeDiffApi(id, note) {
  return request.post(`/inv/pick-diffs/${id}/close`, null, { params: { note } })
}

// ---------- 冻结挂起恢复（spec freeze-management ADDED 需求①） ----------
export function resumeWaveApi(id) {
  return request.post(`/inv/waves/${id}/resume`)
}
