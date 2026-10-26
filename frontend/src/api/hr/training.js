import request from '@/utils/request'

// ============ 培训认证（add-training-certification） ============

// ---------- 10.9.1 培训计划（training-plan-execution） ----------

export function planList(params) {
  return request({ url: '/api/hr/train/plans', method: 'get', params })
}
export function planCreate(data) {
  return request({ url: '/api/hr/train/plans', method: 'post', data })
}
export function planUpdate(id, data) {
  return request({ url: `/api/hr/train/plans/${id}`, method: 'put', data })
}
export function planCancel(id) {
  return request({ url: `/api/hr/train/plans/${id}/cancel`, method: 'post' })
}
export function planDetail(id) {
  return request({ url: `/api/hr/train/plans/${id}`, method: 'get' })
}
export function trainResult(taskId, data) {
  return request({ url: `/api/hr/train/tasks/${taskId}/result`, method: 'post', data })
}
export function planCost(id, data) {
  return request({ url: `/api/hr/train/plans/${id}/cost`, method: 'post', data })
}
export function trainRates(params) {
  return request({ url: '/api/hr/train/rates', method: 'get', params })
}
export function trainRemindScan() {
  return request({ url: '/api/hr/train/remind-scan', method: 'post' })
}

// ---------- 10.9.2 资格认证（qualification-certification） ----------

export function certCategories() {
  return request({ url: '/api/hr/certs/categories', method: 'get' })
}
export function certCategoryCreate(data) {
  return request({ url: '/api/hr/certs/categories', method: 'post', data })
}
export function certCategoryUpdate(id, data) {
  return request({ url: `/api/hr/certs/categories/${id}`, method: 'put', data })
}
export function certLedger(params) {
  return request({ url: '/api/hr/certs', method: 'get', params })
}
export function certPending() {
  return request({ url: '/api/hr/certs/pending', method: 'get' })
}
export function certAudit(id, data) {
  return request({ url: `/api/hr/certs/${id}/audit`, method: 'post', data })
}
export function certArchive(id) {
  return request({ url: `/api/hr/certs/${id}/archive`, method: 'post' })
}
export function certFlipScan() {
  return request({ url: '/api/hr/certs/flip-scan', method: 'post' })
}

// ---------- 10.9.3 到期预警 + 带病排班处置（attendance-core 门禁） ----------

export function certAlertLists() {
  return request({ url: '/api/hr/certs/alert-lists', method: 'get' })
}
export function sickSchedules() {
  return request({ url: '/api/hr/train/sick-schedules', method: 'get' })
}
export function sickDispose(scheduleId, data) {
  return request({ url: `/api/hr/train/sick-schedules/${scheduleId}/dispose`, method: 'post', data })
}

// ---------- 技能矩阵查询（skill-matrix，供资格盘点复用） ----------

export function skillQuery(params) {
  return request({ url: '/api/hr/skill-matrix/by-skill', method: 'get', params })
}

// ---------- 自助端（/api/my） ----------

export function myCerts() {
  return request({ url: '/api/my/certs', method: 'get' })
}
export function myCertSubmit(data) {
  return request({ url: '/api/my/certs', method: 'post', data })
}
export function myTrainTasks() {
  return request({ url: '/api/my/train-tasks', method: 'get' })
}
export function myTrainConfirm(taskId) {
  return request({ url: `/api/my/train-tasks/${taskId}/confirm`, method: 'post' })
}

// ---------- 通用附件（证书扫描件，复用最小附件能力） ----------

export function uploadCertFile(file, bizId) {
  const form = new FormData()
  form.append('file', file)
  form.append('bizType', 'TRAIN_CERT')
  form.append('bizId', bizId)
  return request({ url: '/api/common/attachments', method: 'post', data: form })
}
export function attachmentDownloadUrl(id) {
  return `/api/common/attachments/download?id=${id}`
}
