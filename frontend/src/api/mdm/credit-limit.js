import request from '@/utils/request'

/** 列表概览（集团行含占用率/超期计数/法人子表，查询前服务端执行懒校验扫描） */
export function getOverviewApi(params) {
  return request.get('/mdm/credit-limits/overview', { params })
}

/** 法人额度调整（常规求和 422 / 临时有效期校验 / 原因必填） */
export function adjustViewLimitApi(data) {
  return request.post('/mdm/credit-limits/view-limit', data)
}

/** 集团基准调整（总额度/评级，改求和基准） */
export function adjustGroupLimitApi(groupId, creditLimitTotal, creditRating, reason) {
  return request.put('/mdm/credit-limits/group-limit', { groupId, creditLimitTotal, creditRating, reason })
}

/** 占用率（BR-4.1-32：>80% over80） */
export function getOccupancyApi(groupId) {
  return request.get('/mdm/credit-limits/group-occupancy', { params: { groupId } })
}

/** 复审通过（更新日期 + 清压缩恢复） */
export function reviewPassedApi(viewId, reason) {
  return request.post('/mdm/credit-limits/review', null, { params: { viewId, reason } })
}

/** 额度时间轴（版本快照聚合） */
export function getTimelineApi(groupId) {
  return request.get('/mdm/credit-limits/timeline', { params: { groupId } })
}

/** 可用额度试算（FR-4.3-2-2 公式 + 桩口径） */
export function getTrialApi(viewId) {
  return request.get('/mdm/credit-limits/trial', { params: { viewId } })
}
