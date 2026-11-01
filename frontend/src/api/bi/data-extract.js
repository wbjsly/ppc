import request from '@/utils/request'

/**
 * 数据抽取控制台（14.2，add-data-extract-console，spec bi-extract-console /
 * bi-mask-rule-management / bi-quality-check）。
 * 写操作后端限 BI_DESIGNER/ADMIN；读登录即可。
 */

// ---------- 数据源（14.2.1，FR-4.10-2-1 / BR-4.10-15） ----------

export function getDatasourcesApi() {
  return request.get('/bi/data-extract/datasources')
}

export function createDatasourceApi(row) {
  return request.post('/bi/data-extract/datasources', row)
}

export function updateDatasourceApi(id, row) {
  return request.put(`/bi/data-extract/datasources/${id}`, row)
}

export function deleteDatasourceApi(id) {
  return request.delete(`/bi/data-extract/datasources/${id}`)
}

/** 连通性测试（DB=本库可达+登记名核对；API/FILE=登记形态） */
export function testConnectionApi(id) {
  return request.post(`/bi/data-extract/datasources/${id}/test-connection`)
}

/** schema 快照比对（不一致 → 暂停其下配置，BR-4.10-15） */
export function checkSchemaApi(id) {
  return request.post(`/bi/data-extract/datasources/${id}/check-schema`)
}

/** 更新快照并恢复 ACTIVE */
export function refreshSnapshotApi(id) {
  return request.post(`/bi/data-extract/datasources/${id}/refresh-snapshot`)
}

// ---------- 抽取配置 ----------

export function getExtractConfigsApi(datasourceId) {
  return request.get('/bi/data-extract/configs', { params: { datasourceId } })
}

export function createExtractConfigApi(row) {
  return request.post('/bi/data-extract/configs', row)
}

export function updateExtractConfigApi(id, row) {
  return request.put(`/bi/data-extract/configs/${id}`, row)
}

export function deleteExtractConfigApi(id) {
  return request.delete(`/bi/data-extract/configs/${id}`)
}

// ---------- 批次执行（BR-4.10-12/13） ----------

export function getRunsApi(date) {
  return request.get('/bi/data-extract/runs', { params: { date } })
}

/** 执行/推进批次（manual=true 手动补跑绕过窗口） */
export function runBatchApi(date, manual = true) {
  return request.post('/bi/data-extract/batch/run', { date, manual })
}

/** 波动暂停人工确认继续 */
export function confirmRunApi(id) {
  return request.post(`/bi/data-extract/runs/${id}/confirm`)
}

/** 暂停任务人工放弃终止 */
export function abortRunApi(id) {
  return request.post(`/bi/data-extract/runs/${id}/abort`)
}

/** 数据就绪状态 */
export function getReadyApi() {
  return request.get('/bi/data-extract/ready')
}

// ---------- 脱敏规则（14.2.2） ----------

export function getMaskRulesApi(ruleType) {
  return request.get('/bi/mask-rules', { params: { ruleType } })
}

export function createMaskRuleApi(row) {
  return request.post('/bi/mask-rules', row)
}

export function updateMaskRuleApi(id, row) {
  return request.put(`/bi/mask-rules/${id}`, row)
}

export function disableMaskRuleApi(id) {
  return request.post(`/bi/mask-rules/${id}/disable`)
}

/** 提交审批（ROLE_DATA_GOV 单签） */
export function submitMaskRuleApi(id) {
  return request.post(`/bi/mask-rules/${id}/submit`)
}

export function getMaskRuleLogsApi(id) {
  return request.get(`/bi/mask-rules/${id}/logs`)
}

// ---------- 质量校验（14.2.3，BR-4.10-14） ----------

export function getQualityRulesApi(status) {
  return request.get('/bi/quality/rules', { params: { status } })
}

export function createQualityRuleApi(row) {
  return request.post('/bi/quality/rules', row)
}

export function updateQualityRuleApi(id, row) {
  return request.put(`/bi/quality/rules/${id}`, row)
}

export function deleteQualityRuleApi(id) {
  return request.delete(`/bi/quality/rules/${id}`)
}

/** 立即校验（单规则手动执行） */
export function runQualityNowApi(id) {
  return request.post(`/bi/quality/rules/${id}/run-now`)
}

/** 批次复验解除阻断（无 SEVERE FAIL 自动置就绪） */
export function recheckBatchApi(batchNo) {
  return request.post(`/bi/quality/recheck/${batchNo}`)
}

export function getQualityResultsApi(params) {
  return request.get('/bi/quality/results', { params })
}

/** 审批签署（复用审批台端点，规则详情抽屉就地签署） */
export function approvalPass(taskId, opinion) {
  return request({ url: '/api/qms/approvals/pass', method: 'post', data: { taskId, opinion } })
}

export function approvalReject(taskId, opinion) {
  return request({ url: '/api/qms/approvals/reject', method: 'post', data: { taskId, opinion } })
}
