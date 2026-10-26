import request from '@/utils/request'

// ============ 项目立项（add-project-initiation，11.3.1） ============

export function projectList(params) {
  return request({ url: '/api/pm/projects', method: 'get', params })
}
export function projectCreate(data) {
  return request({ url: '/api/pm/projects', method: 'post', data })
}
export function projectDetail(id) {
  return request({ url: `/api/pm/projects/${id}`, method: 'get' })
}
export function projectUpdate(id, data) {
  return request({ url: `/api/pm/projects/${id}`, method: 'put', data })
}
export function projectDelete(id) {
  return request({ url: `/api/pm/projects/${id}`, method: 'delete' })
}

// ---------- 附件（IP 归属协议等，BIZ_TYPE=PM_IP_AGREEMENT） ----------

export function uploadAttachment(file, bizType, bizId) {
  const form = new FormData()
  form.append('file', file)
  form.append('bizType', bizType)
  form.append('bizId', bizId)
  return request({ url: '/api/common/attachments', method: 'post', data: form })
}
export function attachmentList(bizType, bizId) {
  return request({ url: '/api/common/attachments', method: 'get', params: { bizType, bizId } })
}
export function attachmentDownloadUrl(id) {
  return `/api/common/attachments/download?id=${id}`
}
