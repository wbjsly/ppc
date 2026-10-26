import request from '@/utils/request'

// ---------- 里程碑管理（add-milestone-management，M11.4；11.4.1~3） ----------

export function listMilestones(projectId) {
  return request({ url: '/api/pm/milestones', method: 'get', params: { projectId } })
}

export function createMilestone(data) {
  return request({ url: '/api/pm/milestones', method: 'post', data })
}

export function updateMilestone(id, data) {
  return request({ url: `/api/pm/milestones/${id}`, method: 'put', data })
}

export function deleteMilestone(id) {
  return request({ url: `/api/pm/milestones/${id}`, method: 'delete' })
}

export function submitMilestone(id) {
  return request({ url: `/api/pm/milestones/${id}/submit`, method: 'post' })
}

export function reviewMilestone(id, data) {
  return request({ url: `/api/pm/milestones/${id}/review`, method: 'post', data })
}

export function milestoneDeliverables(id) {
  return request({ url: `/api/pm/milestones/${id}/deliverables`, method: 'get' })
}

export function remindMilestone(id) {
  return request({ url: `/api/pm/milestones/${id}/remind`, method: 'post' })
}

export function milestoneProgress(projectId) {
  return request({ url: '/api/pm/milestones/progress', method: 'get', params: { projectId } })
}

// WBS 阶段节点下拉（level=1）
export function wbsTree(projectId) {
  return request({ url: '/api/pm/wbs', method: 'get', params: { projectId } })
}

// 项目下拉
export function pmProjects() {
  return request({ url: '/api/pm/projects', method: 'get' })
}

// 交付物上传（复用统一附件底座）
export function uploadDeliverable(file, milestoneId) {
  const form = new FormData()
  form.append('file', file)
  form.append('bizType', 'MILESTONE_DELIV')
  form.append('bizId', milestoneId)
  return request({ url: '/api/common/attachments', method: 'post', data: form })
}

export function attachmentDownloadUrl(id) {
  return `/api/common/attachments/download?id=${id}`
}

// 审批（通用底座，BIZ_TYPE=PmMilestone）
export function approvalTodo() {
  return request({ url: '/api/qms/approvals/todo', method: 'get' })
}

export function approvalPass(taskId, opinion) {
  return request({ url: '/api/qms/approvals/pass', method: 'post', data: { taskId, opinion } })
}

export function approvalReject(taskId, reason) {
  return request({ url: '/api/qms/approvals/reject', method: 'post', data: { taskId, reason } })
}
