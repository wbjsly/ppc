import request from '@/utils/request'

/** 不合格品 NCR（2.5.3 / 6.5.1 / 6.5.2，spec ncr-management） */

/** 分页（状态/严重度/关键字） */
export function getNcrPageApi(params) {
  return request.get('/qms/ncrs', { params })
}

/** 详情（检验批快照/检验项/CAPA/复检批/退货与让步单/日志） */
export function getNcrDetailApi(id) {
  return request.get(`/qms/ncrs/${id}`)
}

/** 操作日志 */
export function getNcrLogsApi(id) {
  return request.get(`/qms/ncrs/${id}/logs`)
}

/** 评审：必须选定处置（安全/法规 CTQ 禁让步 BR-4.12-26） */
export function reviewNcrApi(id, disposition, opinion) {
  return request.post(`/qms/ncrs/${id}/review`, { disposition, opinion })
}

/** 处置方案录入（挑选/返工） */
export function planNcrApi(id, plan) {
  return request.post(`/qms/ncrs/${id}/disposition-plan`, { plan })
}

/** 处置执行确认（凭证齐全 → DISPOSED；挑选/返工生成复检批） */
export function confirmNcrApi(id, result) {
  return request.post(`/qms/ncrs/${id}/dispose-confirm`, { result })
}

/** 关闭（CAPA 有效 + 复检放行 → CLOSED 解冻只读） */
export function closeNcrApi(id, opinion) {
  return request.post(`/qms/ncrs/${id}/close`, { opinion })
}

/** 作废（仅评审前，填原因；解冻） */
export function cancelNcrApi(id, reason) {
  return request.post(`/qms/ncrs/${id}/cancel`, { reason })
}
