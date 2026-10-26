import request from '@/utils/request'

// ========== 归集（5.11.1-3，spec production-cost-aggregation） ==========

/** 归集列表（keyword=工单号/物料，period=yyyyMM） */
export function listCostApi(params) {
  return request.get('/mrp/cost/list', { params })
}

/** 归集详情（summary + 明细行） */
export function costDetailApi(moId) {
  return request.get(`/mrp/cost/${moId}`)
}

/** 手动重跑归集（重建式，不触凭证；写权限 COST_ACCOUNTANT/ADMIN） */
export function rerunCostApi(moId) {
  return request.post(`/mrp/cost/${moId}/rerun`)
}

/** 差异报告主管审阅（RAISED → REVIEWED；PLAN_MGR/ADMIN） */
export function reviewVarianceApi(moId) {
  return request.post(`/mrp/cost/${moId}/review`)
}

/** 制造费用池列表（期次倒序） */
export function listPoolsApi() {
  return request.get('/mrp/cost/pools')
}

/** 制造费用池录入/修改（期次唯一 upsert） */
export function savePoolApi(body) {
  return request.post('/mrp/cost/pools', body)
}

// ========== 完工凭证（5.11.4，spec completion-cost-voucher） ==========

/** 完工凭证列表（status/period 筛选） */
export function listVouchersApi(params) {
  return request.get('/mrp/cost/vouchers', { params })
}

/** 待过账（DRAFT）清单 */
export function voucherDraftsApi() {
  return request.get('/mrp/cost/vouchers/drafts')
}

/** 凭证头行明细 */
export function voucherDetailApi(voucherId) {
  return request.get(`/mrp/cost/vouchers/${voucherId}`)
}

/** DRAFT 下期过账（COST_ACCOUNTANT/ADMIN） */
export function postVoucherApi(voucherId) {
  return request.post(`/mrp/cost/vouchers/${voucherId}/post`)
}

/** 月度差异转销（BR-4.5-29；同月幂等 422） */
export function varianceTransferApi(period) {
  return request.post('/mrp/cost/variance-transfer', null, { params: { period } })
}
