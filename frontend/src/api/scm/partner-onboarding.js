import request from '@/utils/request'

// ---------- 伙伴入驻（add-partner-onboarding，M12.1） ----------

// ---- 12.1.1 伙伴注册 ----

/** 申请单分页（status/keyword） */
export function applyPage(params) {
  return request({ url: '/api/proc/partner-applies', method: 'get', params })
}

/** 申请单详情（含证照/激活链接状态/审批日志） */
export function applyDetail(id) {
  return request({ url: `/api/proc/partner-applies/${id}`, method: 'get' })
}

/** 保存草稿（信用代码 18 位+重复 L1 校验） */
export function saveApply(data) {
  return request({ url: '/api/proc/partner-applies', method: 'post', data })
}

/** 提交审批（风险分级动态组链） */
export function submitApply(id) {
  return request({ url: `/api/proc/partner-applies/${id}/submit`, method: 'post' })
}

/** 撤回（APPROVING → DRAFT） */
export function withdrawApply(id) {
  return request({ url: `/api/proc/partner-applies/${id}/withdraw`, method: 'put' })
}

/** 保存证照行（附件经 /api/common/attachments 上传，bizType=PARTNER_APPLY_CERT） */
export function saveCert(data) {
  return request({ url: '/api/proc/partner-applies/certs', method: 'post', data })
}

/** 删除证照行（仅 DRAFT） */
export function deleteCert(certId) {
  return request({ url: `/api/proc/partner-applies/certs/${certId}`, method: 'delete' })
}

/** 激活链接（链接+状态，PROCUREMENT/ADMIN） */
export function activateLink(applyId) {
  return request({ url: `/api/proc/partner-applies/${applyId}/activate-link`, method: 'get' })
}

/** 重新生成激活链接（旧链立即失效，BR-4.9-11） */
export function regenerateLink(applyId) {
  return request({ url: `/api/proc/partner-applies/${applyId}/activate-link/regenerate`, method: 'post' })
}

// ---- 审批（复用审批底座，bizType=PARTNER_ADMISSION） ----

/** 当前用户审批待办 */
export function approvalTodo() {
  return request({ url: '/api/qms/approvals/todo', method: 'get' })
}

/** 节点通过（taskId/opinion 走 body） */
export function approvalPass(taskId, opinion) {
  return request({ url: '/api/qms/approvals/pass', method: 'post', data: { taskId, opinion } })
}

/** 节点驳回（意见 ≥2 字，整单驳回） */
export function approvalReject(taskId, reason) {
  return request({ url: '/api/qms/approvals/reject', method: 'post', data: { taskId, reason } })
}

/** 按业务类型列审批实例（审核工作台列表） */
export function approvalHistory(status) {
  return request({
    url: '/api/qms/approvals/history',
    method: 'get',
    params: { bizType: 'PARTNER_ADMISSION', status }
  })
}

// ---- 12.1.3 通道开通 ----

/** 通道列表（partnerCode 可选） */
export function channelList(partnerCode) {
  return request({
    url: '/api/proc/partner-channels',
    method: 'get',
    params: partnerCode ? { partnerCode } : undefined
  })
}

/** 创建通道（默认 SANDBOX；PROD 切换走放行会签） */
export function createChannel(data) {
  return request({ url: '/api/proc/partner-channels', method: 'post', data })
}

/** 生成 API Key+Secret（明文仅本次返回，落库加盐哈希） */
export function generateKey(data) {
  return request({ url: '/api/proc/partner-channels/keys', method: 'post', data })
}

/** 更新限流档位（STRATEGIC 500/NORMAL 100/NEW 30） */
export function updateRateTier(id, rateTier) {
  return request({ url: `/api/proc/partner-channels/${id}/rate-tier`, method: 'put', data: { rateTier } })
}

/** 证书指纹与有效期更新（登记台账） */
export function updateCertInfo(id, data) {
  return request({ url: `/api/proc/partner-channels/${id}/cert`, method: 'put', data })
}
