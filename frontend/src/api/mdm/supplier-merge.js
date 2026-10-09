import request from '@/utils/request'

/** 疑似重复候选（税号 + 名称≤3，排除自身与已合并） */
export function getCandidatesApi(keyword, excludeId) {
  return request.get('/mdm/supplier-merges/candidates', { params: { keyword, excludeId } })
}

/** 差异对比并排 + 方向建议 */
export function getCompareApi(sourceId, targetId) {
  return request.get('/mdm/supplier-merges/compare', { params: { sourceId, targetId } })
}

/** 影响面：证照计数 + 四类迁移桩 */
export function getMergeImpactApi(sourceId) {
  return request.get('/mdm/supplier-merges/impact', { params: { sourceId } })
}

/** 合并执行（单事务） */
export function mergeApi(sourceId, targetId, reason) {
  return request.post('/mdm/supplier-merges/merge', { sourceId, targetId, reason })
}

/** 30 天回退 */
export function revertApi(logId, reason) {
  return request.post('/mdm/supplier-merges/revert', { logId, reason })
}

/** 合并日志分页 */
export function getLogPageApi(params) {
  return request.get('/mdm/supplier-merges', { params })
}
