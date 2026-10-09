import request from '@/utils/request'

/** 让步接收（2.5.2 / 6.4.2，spec concession-acceptance） */

export function getConcessionPageApi(params) {
  return request.get('/qms/concessions', { params })
}

export function getConcessionDetailApi(id) {
  return request.get(`/qms/concessions/${id}`)
}

export function getWriteoffsApi(id) {
  return request.get(`/qms/concessions/${id}/writeoffs`)
}

/** 申请（要素必填；仅限本批） */
export function createConcessionApi(data) {
  return request.post('/qms/concessions', data)
}

/** 提交双签（质量经理 + 技术负责人并行，缺一不生效） */
export function submitConcessionApi(id) {
  return request.post(`/qms/concessions/${id}/submit`)
}

/** 作废（仅草稿；记录永久） */
export function cancelConcessionApi(id, reason) {
  return request.post(`/qms/concessions/${id}/cancel`, { reason })
}

/** 核销放行（有效期/累计量/范围逐次校验，越界 422 留痕） */
export function writeOffApi(id, data) {
  return request.post(`/qms/concessions/${id}/writeoffs`, data)
}

/** 质量闸口（ALLOWED / CONCESSION / BLOCKED，D8 桩） */
export function qualityGateApi(data) {
  return request.post('/qms/concessions/quality-gate', data)
}
