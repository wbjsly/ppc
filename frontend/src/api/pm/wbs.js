import request from '@/utils/request'

// ============ WBS 分解与模板（add-project-initiation，11.3.2） ============

export function wbsTree(projectId) {
  return request({ url: '/pm/wbs', method: 'get', params: { projectId } })
}
export function wbsCreate(data) {
  return request({ url: '/pm/wbs', method: 'post', data })
}
export function wbsUpdate(id, data) {
  return request({ url: `/pm/wbs/${id}`, method: 'put', data })
}
export function wbsDelete(id) {
  return request({ url: `/pm/wbs/${id}`, method: 'delete' })
}
export function wbsValidate(projectId) {
  return request({ url: '/pm/wbs/validate', method: 'get', params: { projectId } })
}

// ---------- 模板 ----------

export function templateList(params) {
  return request({ url: '/pm/templates', method: 'get', params })
}
export function templateCreate(data) {
  return request({ url: '/pm/templates', method: 'post', data })
}
export function templateUpdate(id, data) {
  return request({ url: `/pm/templates/${id}`, method: 'put', data })
}
export function templateToggle(id, status) {
  return request({ url: `/pm/templates/${id}/status`, method: 'put', params: { status } })
}
export function templateDelete(id) {
  return request({ url: `/pm/templates/${id}`, method: 'delete' })
}
export function templateGenerate(projectId, templateId, confirm) {
  return request({ url: '/pm/templates/generate', method: 'post',
    params: { projectId, templateId, confirm: confirm ? 'true' : 'false' } })
}

// ---------- 资源负载与产能 ----------

export function resourceLoad(params) {
  return request({ url: '/pm/resources/load', method: 'get', params })
}
export function capacityList(params) {
  return request({ url: '/pm/resources/capacity', method: 'get', params })
}
export function capacitySave(data) {
  return request({ url: '/pm/resources/capacity', method: 'post', data })
}
