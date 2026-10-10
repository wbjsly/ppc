import request from '@/utils/request'

/** 工单列表（status/productCode/shortageFlag 过滤，含全部留痕字段） */
export function getMosApi(params) {
  return request.get('/mrp/mos', { params })
}

/** 工单详情（头 + BOM 快照 + 工序快照 + 缺料清单） */
export function getMoDetailApi(id) {
  return request.get(`/mrp/mos/${id}`)
}

/** 手工新建工单（头卡控 + 双快照 + 缺料预检，返回 {mo, warnings}） */
export function createMoApi(head) {
  return request.post('/mrp/mos', { head })
}

/** 可选 PMO 列表（已转正未关联的生产建议，D6 对接） */
export function getCandidatePmosApi() {
  return request.get('/mrp/mos/candidate-pmos')
}

/** 从 PMO 建单（MO_NO 幂等回写；overrides = 日期/优先级覆盖） */
export function createFromPmoApi(suggestId, overrides) {
  return request.post('/mrp/mos/from-pmo', { suggestId, overrides: overrides || null })
}

/** 缺料清单（FR-4.5-3-6） */
export function getShortagesApi(id) {
  return request.get(`/mrp/mos/${id}/shortages`)
}

/** 提交审批（PLANNED→PENDING） */
export function submitMoApi(id) {
  return request.post(`/mrp/mos/${id}/submit`)
}

/** 释放（CONFIRMED→RELEASED + 重跑齐套打标） */
export function releaseMoApi(id) {
  return request.post(`/mrp/mos/${id}/release`)
}

/** 挂起（原因必填） */
export function holdMoApi(id, reason) {
  return request.post(`/mrp/mos/${id}/hold`, { reason })
}

/** 挂起恢复 */
export function resumeMoApi(id) {
  return request.post(`/mrp/mos/${id}/resume`)
}

/** 取消（原因必填，终态） */
export function cancelMoApi(id, reason) {
  return request.post(`/mrp/mos/${id}/cancel`, { reason })
}

/** 拆分（子单合计=剩余量 L1，C-4.5-15） */
export function splitMoApi(id, childQtys, reason) {
  return request.post(`/mrp/mos/${id}/split`, { childQtys, reason })
}

/** 手动完工确认（RELEASED→COMPLETED + 合格产出） */
export function completeMoApi(id, qualifiedQty) {
  return request.post(`/mrp/mos/${id}/complete`, { qualifiedQty })
}

/** 关闭预检（状态 + 钩子结果） */
export function closePrecheckApi(id) {
  return request.get(`/mrp/mos/${id}/close-precheck`)
}

/** 关闭（→CLOSED 终态） */
export function closeMoApi(id) {
  return request.post(`/mrp/mos/${id}/close`)
}

/** 在制供给查询（下游契约：itemCodes 逗号分隔） */
export function inProcessApi(itemCodes) {
  return request.get('/mrp/mos/in-process', { params: { itemCodes } })
}
