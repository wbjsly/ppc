import request from '@/utils/request'

/** 组合检索（操作人/动作/对象/关键词/时间范围 + 分页 + 脱敏） */
export function searchLogs(params) {
  return request({ url: '/api/system/audit-logs', method: 'get', params })
}

/** 防篡改尝试记录列表 */
export function tamperList(limit) {
  return request({ url: '/api/system/audit-logs/tamper', method: 'get', params: { limit } })
}

/** 尝试行告警扫描（幂等） */
export function tamperSweep() {
  return request({ url: '/api/system/audit-logs/tamper/sweep', method: 'get' })
}

// ---------- 取证导出 ----------

/** 申请列表（含包状态） */
export function listRequests() {
  return request({ url: '/api/system/forensic/requests', method: 'get' })
}

/** 调阅申请 */
export function applyRequest(data) {
  return request({ url: '/api/system/forensic/requests', method: 'post', data })
}

/** 内审签署 */
export function signAudit(id, opinion) {
  return request({ url: `/api/system/forensic/requests/${id}/sign-audit`, method: 'post', data: { opinion } })
}

/** DPO 签署 */
export function signDpo(id, opinion) {
  return request({ url: `/api/system/forensic/requests/${id}/sign-dpo`, method: 'post', data: { opinion } })
}

/** 生成取证包 */
export function generatePackage(id) {
  return request({ url: `/api/system/forensic/requests/${id}/packages`, method: 'post' })
}

/** 分片下载（返回 blob） */
export function downloadShard(packageId, shard) {
  return request({
    url: `/api/system/forensic/packages/${packageId}/shards/${shard}`,
    method: 'get',
    responseType: 'blob'
  })
}

/** 使用完毕确认（提前销毁） */
export function confirmUsed(packageId) {
  return request({ url: `/api/system/forensic/packages/${packageId}/confirm-used`, method: 'post' })
}

/** 过期销毁扫描 */
export function sweepExpired() {
  return request({ url: '/api/system/forensic/sweep-expired', method: 'post' })
}

// ---------- 脱敏规则 ----------

export function listMaskRules() {
  return request({ url: '/api/system/mask-rules', method: 'get' })
}

export function saveMaskRule(data) {
  return request({ url: '/api/system/mask-rules', method: 'post', data })
}

export function deleteMaskRule(id) {
  return request({ url: `/api/system/mask-rules/${id}`, method: 'delete' })
}
