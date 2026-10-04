import request from '@/utils/request'

/** 政策台账（1.6.2） */
export function getPolicyPageApi(params) {
  return request.get('/mdm/tax-policies', { params })
}

export function getPolicyApi(id) {
  return request.get(`/mdm/tax-policies/${id}`)
}

export function createPolicyApi(data) {
  return request.post('/mdm/tax-policies', data)
}

export function updatePolicyApi(data) {
  return request.put('/mdm/tax-policies', data)
}

export function deletePolicyApi(id) {
  return request.delete(`/mdm/tax-policies/${id}`)
}

/** 关联回链：该政策关联的税码清单 + 版本流水倒序 */
export function getPolicyTaxCodesApi(id) {
  return request.get(`/mdm/tax-policies/${id}/tax-codes`)
}

/** 版本快照与两版本对比（1.6.2 增强） */
export function getPolicyVersionsApi(id) {
  return request.get(`/mdm/tax-policies/${id}/versions`)
}

export function getPolicyDiffApi(id, from, to) {
  return request.get(`/mdm/tax-policies/${id}/diff`, { params: { from, to } })
}

/** 工作台：候选税码 / 预检 / 行级提交 */
export function getWorkbenchCandidatesApi(id, params) {
  return request.get(`/mdm/tax-policies/${id}/workbench/candidates`, { params })
}

export function previewWorkbenchApi(id, data) {
  return request.post(`/mdm/tax-policies/${id}/workbench/preview`, data)
}

export function submitWorkbenchApi(id, data) {
  return request.post(`/mdm/tax-policies/${id}/workbench/submit`, data)
}
