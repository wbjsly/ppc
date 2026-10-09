import request from '@/utils/request'

// ============ BI 底座：口径字典 ============
export function getDictApi(params) { return request.get('/bi/dict', { params }) }
export function registerDictApi(data) { return request.post('/bi/dict', data) }
export function publishDictApi(key, formula) { return request.post(`/bi/dict/${key}/publish`, { formula }) }
export function diffDictApi(key, formula) { return request.post(`/bi/dict/${key}/diff`, { formula }) }
export function changeFormulaApi(key, data) { return request.post(`/bi/dict/${key}/formula`, data) }
export function historyDictApi(key) { return request.get(`/bi/dict/${key}/history`) }

// ============ 抽取批次与就绪 ============
export function getBatchApi(date) { return request.get('/bi/extract/batch', { params: { date } }) }
export function runBatchApi(date) { return request.post('/bi/extract/run', { date }) }
export function getReadyApi() { return request.get('/bi/extract/ready') }
export function confirmTaskApi(id) { return request.post(`/bi/extract/tasks/${id}/confirm`, {}) }

// ============ 2.9.1 成本分析 ============
export function getCompositionApi(params) { return request.get('/bi/cost/composition', { params }) }
export function getPriceDiffApi(params) { return request.get('/bi/cost/price-diff', { params }) }
export function getTrendApi(itemCode, basis) { return request.get('/bi/cost/trend', { params: { itemCode, basis } }) }
export function getSavingsApi(monthTag, dimension) { return request.get('/bi/cost/savings', { params: { monthTag, dimension } }) }
export function scanNowApi() { return request.post('/bi/cost/scan', {}) }

// ============ 导出 ============
export function submitExportApi(data) { return request.post('/bi/export', data) }
export function getExportTasksApi() { return request.get('/bi/export/tasks') }
export function approveExportApi(id, data) { return request.post(`/bi/export/${id}/approval`, data) }
export function getPendingExportApi() { return request.get('/bi/export/pending') }

// ============ 2.9.3 价格监测 ============
export function getAlertsApi(params) { return request.get('/bi/monitor/alerts', { params }) }
export function handleAlertApi(id, data) { return request.post(`/bi/monitor/alerts/${id}/handle`, data) }
export function getThresholdApi(key) { return request.get(`/bi/monitor/threshold/${key}`) }
export function setThresholdApi(key, data) { return request.post(`/bi/monitor/threshold/${key}`, data) }
export function getDeviationApi() { return request.get('/bi/monitor/deviation') }
export function getQuoteAnomaliesApi() { return request.get('/bi/monitor/quote-anomalies') }

// ============ 2.9.2 记分卡 ============
export function getModelsApi(params) { return request.get('/scm/scorecard/models', { params }) }
export function saveModelApi(data) { return request.post('/scm/scorecard/models', data) }
export function collectScorecardApi(monthTag) { return request.post('/scm/scorecard/collect', { monthTag }) }
export function getResultsApi(params) { return request.get('/scm/scorecard/results', { params }) }
export function getResultDetailApi(id) { return request.get(`/scm/scorecard/results/${id}`) }
export function reviewResultApi(id, opinion) { return request.post(`/scm/scorecard/results/${id}/review`, { opinion }) }
export function publishResultApi(id) { return request.post(`/scm/scorecard/results/${id}/publish`, {}) }
export function getRectifiesApi(params) { return request.get('/scm/scorecard/rectifies', { params }) }
export function closeRectifyApi(id, note) { return request.post(`/scm/scorecard/rectifies/${id}/close`, { note }) }
export function unfreezeApi(id) { return request.post(`/scm/scorecard/rectifies/${id}/unfreeze`, {}) }
export function reviewAppealApi(id, data) { return request.post(`/scm/scorecard/appeals/${id}/review`, data) }

// ============ 门户记分卡 ============
export function getPortalScorecardsApi() { return request.get('/portal/scorecards') }
export function getPortalScorecardApi(id) { return request.get(`/portal/scorecards/${id}`) }
export function portalAppealApi(id, data) { return request.post(`/portal/scorecards/${id}/appeal`, data) }
