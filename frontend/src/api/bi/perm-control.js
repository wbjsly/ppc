import request from '@/utils/request'

/**
 * 权限控制台（14.4，add-perm-control-console，spec bi-row-permission /
 * bi-temp-grant / bi-query-governance MODIFIED）。
 * 写操作后端限 BI_DESIGNER/ADMIN；读登录即可。
 */

// ---------- 行级权限（14.4.1） ----------

export function getRowPermsApi(params) {
  return request.get('/bi/row-perms', { params })
}

export function createRowPermApi(row) {
  return request.post('/bi/row-perms', row)
}

export function updateRowPermApi(id, row) {
  return request.put(`/bi/row-perms/${id}`, row)
}

export function submitRowPermApi(id) {
  return request.post(`/bi/row-perms/${id}/submit`)
}

export function disableRowPermApi(id) {
  return request.delete(`/bi/row-perms/${id}`)
}

export function getRowPermVersionsApi(id) {
  return request.get(`/bi/row-perms/${id}/versions`)
}

/** 生效影响预览（并集范围 + 生效规则来源，BR-4.10-16） */
export function impactPreviewApi(userName) {
  return request.get('/bi/row-perms/impact-preview', { params: { userName } })
}

// ---------- 敏感字段登记位（14.4.2） ----------

export function getSensitiveFieldsApi() {
  return request.get('/bi/sensitive-fields')
}

export function createSensitiveFieldApi(row) {
  return request.post('/bi/sensitive-fields', row)
}

export function submitSensitiveFieldApi(id) {
  return request.post(`/bi/sensitive-fields/${id}/submit`)
}

export function disableSensitiveFieldApi(id) {
  return request.delete(`/bi/sensitive-fields/${id}`)
}

export function getSensitiveFieldLogsApi(id) {
  return request.get(`/bi/sensitive-fields/${id}/logs`)
}

// ---------- 掩码规则总览（14.4.3，复用 14.2.2 后端） ----------

export function getMaskRulesApi(ruleType) {
  return request.get('/bi/mask-rules', { params: { ruleType } })
}

// ---------- 临时授权（14.4.3，spec bi-temp-grant ≤24h） ----------

export function applyTempGrantApi(row) {
  return request.post('/bi/temp-grants', row)
}

export function listMyTempGrantsApi() {
  return request.get('/bi/temp-grants/mine')
}

export function listAllTempGrantsApi(status) {
  return request.get('/bi/temp-grants', { params: { status } })
}

export function revokeTempGrantApi(id) {
  return request.delete(`/bi/temp-grants/${id}`)
}

export function expireScanApi() {
  return request.post('/bi/temp-grants/expire-scan')
}
