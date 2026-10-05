import request from '@/utils/request'

/** CAPA/8D（6.6.1~6.6.3，spec capa-management） */

export function getCapaPageApi(params) {
  return request.get('/qms/capa', { params })
}

export function getCapaDetailApi(id) {
  return request.get(`/qms/capa/${id}`)
}

/** 手工立项（投诉/审核/关联 NCR） */
export function createCapaApi(data) {
  return request.post('/qms/capa', data)
}

/** 8D 步骤推进（不可跳序；D2 5W2H / D4 根因证据 / D7 验证结论） */
export function advanceStepApi(capaId, data) {
  return request.post(`/qms/capa/${capaId}/steps`, data)
}

/** 录入纠正/预防措施 */
export function addActionApi(capaId, data) {
  return request.post(`/qms/capa/${capaId}/actions`, data)
}

/** 措施完成（标准变更类须审批已通过） */
export function completeActionApi(actionId, effectDesc) {
  return request.post(`/qms/capa/actions/${actionId}/done`, { effectDesc })
}
