import request from '@/utils/request'

/** 告警/降权列表（status: ACTIVE/RESTORED/DISABLED，分页倒序） */
export function listIncidents(params) {
  return request({ url: '/api/system/authz-incidents', method: 'get', params })
}

/** 处置三动作（action=MISUSE/RESTORE/MALICIOUS；conclusion ≥10 字） */
export function disposeIncident(id, data) {
  return request({ url: `/api/system/authz-incidents/${id}/dispose`, method: 'post', data })
}

/** 手动提前恢复（幂等） */
export function restoreIncident(id, reason) {
  return request({ url: `/api/system/authz-incidents/${id}/restore`, method: 'post', data: { reason } })
}

/** 到期恢复扫描（7 天届满，幂等） */
export function sweepRestore() {
  return request({ url: '/api/system/authz-incidents/sweep-restore', method: 'post' })
}
