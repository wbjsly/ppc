import request from '@/utils/request'

// 采购订单 API（2.3.1~2.3.5，change add-framework-agreement-order）

/** 分页（status/source/keyword 过滤） */
export function getPoPageApi(params) {
  return request.get('/proc/purchase-orders', { params })
}

/** 详情（头 + 行） */
export function getPoDetailApi(id) {
  return request.get(`/proc/purchase-orders/${id}`)
}

/** 从协议下单（带出锁定价 + 余量校验 S-4.2-03） */
export function createFromAgreementApi(payload) {
  return request.post('/proc/purchase-orders/agreement-orders', payload)
}

/** RFQ 中选转 PO（消费 awarded 桩） */
export function createFromRfqApi(prNo) {
  return request.post('/proc/purchase-orders/rfq-orders', { prNo })
}

/** 手工创建（BR-4.2-18 供应商合格卡控） */
export function createManualPoApi(payload) {
  return request.post('/proc/purchase-orders', payload)
}

/** 提交审批（价控三重 → 三档判级；specialReason = 特批转升级链） */
export function submitPoApi(id, specialReason) {
  return request.post(`/proc/purchase-orders/${id}/submit`, specialReason ? { specialReason } : {})
}

/** 手工关闭（偏差 D5） */
export function closePoApi(id, reason) {
  return request.post(`/proc/purchase-orders/${id}/close`, { reason })
}

// ---------- 2.3.2 审批 ----------

/** 审批待办（ACTIVE 节点 + 路由链 + 判级 + 价控结果） */
export function getApprovalTodoApi() {
  return request.get('/proc/purchase-orders/approval-todo')
}

/** 节点通过（末节点 → 已批准） */
export function passTaskApi(taskId) {
  return request.post(`/proc/purchase-orders/tasks/${taskId}/pass`)
}

/** 条件批准（附加条件 ≥2 字） */
export function passConditionalApi(taskId, conditionText) {
  return request.post(`/proc/purchase-orders/tasks/${taskId}/pass-conditional`, { conditionText })
}

/** 节点驳回（原因 ≥2 字，PO 回草稿） */
export function rejectTaskApi(taskId, reason) {
  return request.post(`/proc/purchase-orders/tasks/${taskId}/reject`, { reason })
}

/** 按 PO 的审批日志 */
export function getApprovalLogsApi(id) {
  return request.get(`/proc/purchase-orders/${id}/approval-logs`)
}

// ---------- 2.3.3/2.3.4 变更与版本 ----------

/** 发起变更（五类型；金额增加分级审批，调减留痕 BR-4.2-04） */
export function changePoApi(id, payload) {
  return request.post(`/proc/purchase-orders/${id}/changes`, payload)
}

/** 版本列表 */
export function getVersionsApi(id) {
  return request.get(`/proc/purchase-orders/${id}/versions`)
}

/** 变更审批（驳回自动回退 FR-4.2-9-2） */
export function approveChangeApi(id, versionNo, approved, reason) {
  return request.post(`/proc/purchase-orders/${id}/versions/${versionNo}/approve`,
    { approved, reason })
}

/** 回滚到历史版本（BR-4.2-03 生成新版本） */
export function rollbackApi(id, versionNo, reason) {
  return request.post(`/proc/purchase-orders/${id}/versions/${versionNo}/rollback`, { reason })
}

// ---------- 2.3.5 价控 ----------

/** 价控三段日志（BR-4.2-19） */
export function getPriceLogsApi(id) {
  return request.get(`/proc/purchase-orders/${id}/price-logs`)
}
