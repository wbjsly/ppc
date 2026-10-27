import request from '@/utils/request'

// ---------- 结项归档（add-project-closure，M11.12） ----------

// ---- 11.12.1 结项检查 ----

/** 发起结项（ACTIVE→CLOSING，生成检查单+六项证据） */
export function initiateClosure(projectId) {
  return request({ url: `/api/pm/closure/${projectId}/initiate`, method: 'post' })
}

/** 检查单详情（含行/遗留/结算状态） */
export function closureDetail(projectId) {
  return request({ url: `/api/pm/closure/${projectId}`, method: 'get' })
}

/** 重新校验（DRAFT 态重算自动项，人工项保留） */
export function resubmitClosure(projectId) {
  return request({ url: `/api/pm/closure/${projectId}/resubmit`, method: 'post' })
}

/** 人工项确认（PASS 必传附件） */
export function confirmItem(itemId, data) {
  return request({ url: `/api/pm/closure/items/${itemId}`, method: 'put', data })
}

/** 新增自定义非强制检查项 */
export function addCustomItem(projectId, data) {
  return request({ url: `/api/pm/closure/${projectId}/items`, method: 'post', data })
}

/** 删除自定义检查项 */
export function removeCustomItem(itemId) {
  return request({ url: `/api/pm/closure/items/${itemId}/remove`, method: 'put' })
}

/** 提交审批（强制项全过+已结算+总结报告齐 → PMO） */
export function submitClosure(projectId) {
  return request({ url: `/api/pm/closure/${projectId}/submit`, method: 'post' })
}

/** 撤回结项（CLOSING→ACTIVE，审批单取消） */
export function withdrawClosure(projectId) {
  return request({ url: `/api/pm/closure/${projectId}/withdraw`, method: 'post' })
}

/** 遗留项草稿批量保存（PMO 审批前，通过时转 OPEN） */
export function saveRemediations(projectId, items) {
  return request({ url: `/api/pm/closure/${projectId}/remediations`, method: 'put', data: items })
}

/** 遗留工单列表 */
export function remediationList(projectId) {
  return request({ url: `/api/pm/closure/${projectId}/remediations`, method: 'get' })
}

// ---- 11.12.2 成本结算 ----

/** 成本结算汇总（归集/基线/偏差/冻结状态/已结算快照） */
export function settlementSummary(projectId) {
  return request({ url: `/api/pm/closure/${projectId}/settlement/summary`, method: 'get' })
}

/** 财务确认结算（解冻+不可变快照，FINANCE/FIN_MGR） */
export function confirmSettlement(projectId) {
  return request({ url: `/api/pm/closure/${projectId}/settlement/confirm`, method: 'post' })
}

// ---- 附件（BIZ_TYPE=PROJECT_CLOSURE；人工项 bizId=itemId，总结报告 bizId=projectId） ----

/** 上传附件（multipart） */
export function uploadAttach(file, bizType, bizId) {
  const fd = new FormData()
  fd.append('file', file)
  return request({
    url: '/api/common/attachments', method: 'post', data: fd,
    params: { bizType, bizId }
  })
}

/** 按业务键查附件列表 */
export function attachList(bizType, bizId) {
  return request({ url: '/api/common/attachments', method: 'get', params: { bizType, bizId } })
}
