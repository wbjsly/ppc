import request from '@/utils/request'

/**
 * 自助查询控制台（14.6，add-adhoc-query-console，spec bi-adhoc-query +
 * bi-temp-report）。查询/我的查询/临时报表登录即可（查询层治理兜底）；
 * governance 统计后端限 BI_DESIGNER/ADMIN。
 */

// ---------------- 14.6.1 即席查询 ----------------

/** 数据集列表（PUBLISHED+行级可达，含字段语义树） */
export function getDatasetsApi() {
  return request.get('/bi/adhoc/datasets')
}

/** 发起查询（同步返回 rows 或入队返回 taskId） */
export function runQueryApi(templateId, queryJson) {
  return request.post('/bi/adhoc/query', { templateId, queryJson })
}

/** 我的任务（含 REJECTED 阻断留痕） */
export function getMyTasksApi() {
  return request.get('/bi/adhoc/tasks')
}

/** 任务详情+结果集分页 */
export function getTaskDetailApi(id, page = 1, size = 50) {
  return request.get(`/bi/adhoc/tasks/${id}`, { params: { page, size } })
}

// ---------------- 14.6.2 查询治理 ----------------

/** 用量治理（配额余量+按用户汇总） */
export function getGovernanceApi() {
  return request.get('/bi/adhoc/governance')
}

// ---------------- 我的查询 ----------------

export function getSavedQueriesApi() {
  return request.get('/bi/adhoc/saved-queries')
}

export function saveQueryApi(row) {
  return request.post('/bi/adhoc/saved-queries', row)
}

export function deleteSavedQueryApi(id) {
  return request.delete(`/bi/adhoc/saved-queries/${id}`)
}

// ---------------- 14.6.3 临时报表 ----------------

export function getTempReportsApi() {
  return request.get('/bi/temp-reports')
}

export function saveAsTempApi(row) {
  return request.post('/bi/temp-reports', row)
}

export function openTempApi(id, page = 1, size = 50) {
  return request.get(`/bi/temp-reports/${id}`, { params: { page, size } })
}

export function shareTempApi(id, shareList) {
  return request.post(`/bi/temp-reports/${id}/share`, shareList)
}

export function promoteTempApi(id) {
  return request.post(`/bi/temp-reports/${id}/promote`)
}
