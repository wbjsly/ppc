import request from '@/utils/request'

/** 检验批（2.5.1 / 6.2 / 6.3 / 6.4，spec inspection-lot） */

/** 检验批看板（数据源=检验批，D1 起算点=登记提交时间） */
export function getLotBoardApi(params) {
  return request.get('/qms/lots/board', { params })
}

/** 检验批分页（按状态/类型/物料筛选） */
export function getLotPageApi(params) {
  return request.get('/qms/lots', { params })
}

/** 检验批详情（含检验项快照与录入结果） */
export function getLotDetailApi(id) {
  return request.get(`/qms/lots/${id}`)
}

/** BLOCKED 激活（标准发布后转待检） */
export function activateLotApi(id) {
  return request.post(`/qms/lots/${id}/activate`)
}

/** 检验录入（CTQ 必录实测值；abnormalConfirm 二次确认） */
export function inputLotApi(id, items, abnormalConfirm = false) {
  return request.post(`/qms/lots/${id}/input`, { items, abnormalConfirm })
}

/** 自动判定 */
export function judgeLotApi(id) {
  return request.post(`/qms/lots/${id}/judge`)
}

/** 边界复核（质量工程师，L2） */
export function reviewLotApi(id, pass, opinion) {
  return request.post(`/qms/lots/${id}/review`, { pass, opinion })
}

/** 质检员确认合格放行（不可撤回） */
export function releaseLotApi(id) {
  return request.post(`/qms/lots/${id}/release`)
}

/** 手工创建 IPQC/OQC 批次 */
export function createManualLotApi(data) {
  return request.post('/qms/lots', data)
}

/** 严格度记录（加严/解除历史） */
export function getStrictnessApi(params) {
  return request.get('/qms/lots/strictness', { params })
}

/** 巡检计划 */
export function getPatrolPlansApi() {
  return request.get('/qms/lots/patrol-plans')
}

export function createPatrolPlanApi(data) {
  return request.post('/qms/lots/patrol-plans', data)
}

export function togglePatrolPlanApi(id) {
  return request.post(`/qms/lots/patrol-plans/${id}/toggle`)
}

/** 免检 */
export function getExemptsApi(params) {
  return request.get('/qms/exempts', { params })
}

export function applyExemptApi(data) {
  return request.post('/qms/exempts', data)
}
