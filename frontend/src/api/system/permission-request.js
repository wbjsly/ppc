import request from '@/utils/request'

// ---------- 权限审批（add-permission-approval-flow，16.3） ----------

/** 申请单列表（status/keyword 过滤） */
export function requestList(params) {
  return request({ url: '/api/system/perm-requests', method: 'get', params })
}

/** 提交常规权限申请（四字段校验 + 风险路由 + 强制预检快照） */
export function submitRequest(data) {
  return request({ url: '/api/system/perm-requests', method: 'post', data })
}

/** 提交超基线例外申请（KIND=EXCEPTION，16.2 调整改道入口） */
export function submitException(data) {
  return request({ url: '/api/system/perm-requests/exception', method: 'post', data })
}

/** 申请单详情（预检快照 + 审批日志） */
export function requestDetail(id) {
  return request({ url: `/api/system/perm-requests/${id}`, method: 'get' })
}

/** 指定时点生效手动扫描（定时任务默认关） */
export function sweepActivation() {
  return request({ url: '/api/system/perm-requests/sweep-activation', method: 'post' })
}

/** 预检沙盘（超基线 / SoD 冲突 / 现有权限） */
export function precheck(data) {
  return request({ url: '/api/system/sod-rules/precheck', method: 'post', data })
}

/** SoD 互斥规则列表 */
export function sodRuleList() {
  return request({ url: '/api/system/sod-rules', method: 'get' })
}

/** 新增互斥规则 */
export function addSodRule(data) {
  return request({ url: '/api/system/sod-rules', method: 'post', data })
}

/** 删除互斥规则 */
export function deleteSodRule(id) {
  return request({ url: `/api/system/sod-rules/${id}`, method: 'delete' })
}

/** 授予台账列表（ACTIVE/RECLAIMED/EXPIRED，空=全部） */
export function ledgerList(params) {
  return request({ url: '/api/system/grant-ledger', method: 'get', params })
}

/** 手动回收（幂等，回收前留权限快照） */
export function reclaimLedger(id) {
  return request({ url: `/api/system/grant-ledger/${id}/reclaim`, method: 'post' })
}

/** 到期回收 + 7 日提醒手动扫描 */
export function sweepLedger() {
  return request({ url: '/api/system/grant-ledger/sweep', method: 'post' })
}

/** 变更窗口列表 */
export function windowList() {
  return request({ url: '/api/system/change-windows', method: 'get' })
}

/** 新建/更新变更窗口 */
export function saveWindow(data) {
  return request({ url: '/api/system/change-windows', method: 'post', data })
}

/** 删除变更窗口 */
export function deleteWindow(id) {
  return request({ url: `/api/system/change-windows/${id}`, method: 'delete' })
}

/** 审批签署（复用既有审批台端点，详情抽屉就地签署） */
export function approvalPass(taskId, opinion) {
  return request({ url: '/api/qms/approvals/pass', method: 'post', data: { taskId, opinion } })
}

/** 审批驳回（意见 ≥2 字） */
export function approvalReject(taskId, opinion) {
  return request({ url: '/api/qms/approvals/reject', method: 'post', data: { taskId, opinion } })
}

/** 窗口外变更复核台账（16.4.3 只读证据面） */
export function outWindowLedger() {
  return request({ url: '/api/system/perm-requests/out-window-ledger', method: 'get' })
}
