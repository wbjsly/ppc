import request from '@/utils/request'

// ---------- 项目变更管理（add-project-change，M11.7） ----------

// ---- 变更申请（11.7.1） ----

/** 变更单清单（按项目/状态/类型筛选） */
export function changeList(params) {
  return request({ url: '/api/pm/changes', method: 'get', params })
}

/** 变更单详情（含受影响 WBS 集） */
export function changeDetail(id) {
  return request({ url: `/api/pm/changes/${id}`, method: 'get' })
}

/** 保存草稿（WBS 关联可暂缺；倒签 L1；仅 ACTIVE 项目） */
export function saveDraft(data) {
  return request({ url: '/api/pm/changes/draft', method: 'post', data })
}

/** 提交审批（WBS 必填 L1；服务端算预算占比并三级路由；第三档校验客户书面附件） */
export function submitChange(data) {
  return request({ url: '/api/pm/changes/submit', method: 'post', data })
}

/** 执行推进：执行中 → 已完成（仅变更发起人/PM） */
export function advanceChange(id, data) {
  return request({ url: `/api/pm/changes/${id}/advance`, method: 'put', data })
}

/** PMO 核验：已完成 → 已验证（偏差>50% L4 升级通知 PMO） */
export function verifyChange(id, data) {
  return request({ url: `/api/pm/changes/${id}/verify`, method: 'put', data })
}

/** 变更台账统计（按类型/原因/状态分组计数） */
export function changeStats(projectId) {
  return request({ url: '/api/pm/changes/stats', method: 'get', params: { projectId } })
}

// ---- 基线版本（BR-4.8-31 只读） ----

/** 项目全部基线版本（版本号倒序，含当前生效标记） */
export function baselineVersions(projectId) {
  return request({ url: `/api/pm/changes/${projectId}/baseline-versions`, method: 'get' })
}

/** 单版本全量预算快照（只读，历史版永久可查） */
export function baselineSnapshot(projectId, verNoLabel) {
  return request({
    url: `/api/pm/changes/${projectId}/baseline-versions/${verNoLabel}`,
    method: 'get'
  })
}

/** 当前生效基线版本 */
export function baselineCurrent(projectId) {
  return request({ url: `/api/pm/changes/${projectId}/baseline-versions/current`, method: 'get' })
}
