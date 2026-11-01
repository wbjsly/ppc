import request from '@/utils/request'

/**
 * 指标口径字典控制台（14.3，add-metric-dict-console，spec bi-metric-dictionary +
 * bi-metric-lineage）。写操作后端限 BI_DESIGNER/ADMIN；diff/批量校验/血缘读登录即可。
 */

// ---------------- 14.3.1 口径定义 ----------------

/** 指标分页（仅当前版本） */
export function getMetricsApi(params) {
  return request.get('/bi/dict', { params })
}

/** 登记/更新指标（十二要素；新 key 编码格式校验 M-<域>-<序号>） */
export function registerMetricApi(row) {
  return request.post('/bi/dict', row)
}

/** 发布（RATIO 型分子分母必填；diff 422/C-4.10-01） */
export function publishMetricApi(key, formula) {
  return request.post(`/bi/dict/${key}/publish`, { formula })
}

/** diff 校验（只读） */
export function diffCheckApi(key, formula) {
  return request.post(`/bi/dict/${key}/diff`, { formula })
}

/** 历史版本（行级回溯） */
export function getHistoryApi(key) {
  return request.get(`/bi/dict/${key}/history`)
}

/** 引用方状态（口径变更提示数据源） */
export function getConsumersApi(key) {
  return request.get(`/bi/dict/${key}/consumers`)
}

/** 引用方更新确认（清除 PENDING_UPDATE） */
export function confirmConsumerApi(key, cid) {
  return request.put(`/bi/dict/${key}/consumers/${cid}/confirm`)
}

// ---------------- 14.3.2 口径变更（两段式治理 + 血缘） ----------------

/** 发起口径变更（CHANGE_PENDING + 治理单签，旧版仍生效） */
export function requestChangeApi(key, row) {
  return request.post(`/bi/dict/${key}/change-request`, row)
}

/** 变更列表（灰度看板） */
export function getChangesApi(key) {
  return request.get(`/bi/dict/${key}/changes`)
}

/** 切换确认（C-4.10-08 双审；超 0.5% 须治理复核备注） */
export function switchConfirmApi(rowId, row) {
  return request.post(`/bi/dict/changes/${rowId}/switch`, row)
}

/** 终止灰度（新版 ABORTED，旧版续用） */
export function abortChangeApi(rowId, reason) {
  return request.post(`/bi/dict/changes/${rowId}/abort`, { reason })
}

/** 血缘当前图谱 */
export function getLineageApi(key) {
  return request.get(`/bi/dict/${key}/lineage`)
}

/** 血缘历史版本回溯 */
export function getLineageVersionApi(key, version) {
  return request.get(`/bi/dict/${key}/lineage/${version}`)
}

/** 变更影响报告（快照视图 + 高风险标注） */
export function getImpactApi(key, version) {
  return request.get(`/bi/dict/${key}/lineage/${version}/impact`)
}

/** 手工绑定血缘关系（METRIC/TABLE） */
export function bindLineageApi(key, row) {
  return request.post(`/bi/dict/${key}/lineage/bind`, row)
}

// ---------------- 14.3.3 口径校验 ----------------

/** 批量口径校验（PUBLISHED 模板 × 当前版现算报告） */
export function checkAllApi() {
  return request.post('/bi/dict/check-all')
}

/** 审批签署（复用审批台端点，灰度看板就地签署） */
export function approvalPass(taskId, opinion) {
  return request({ url: '/api/qms/approvals/pass', method: 'post', data: { taskId, opinion } })
}

export function approvalReject(taskId, opinion) {
  return request({ url: '/api/qms/approvals/reject', method: 'post', data: { taskId, opinion } })
}
