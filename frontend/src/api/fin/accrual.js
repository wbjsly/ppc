import request from '@/utils/request'

/** 应付暂估（2.7.1，spec ap-accrual）—— 查询 ADMIN/PM；冲回与批次确认仅 ADMIN */

/** 台账分页（供应商/PO/状态/生成日期区间） */
export function getAccrualPageApi(params) {
  return request.get('/fin/accruals', { params })
}

/** 汇总卡：暂估余额 / 本月生成 / 本月冲回 */
export function getAccrualSummaryApi() {
  return request.get('/fin/accruals/summary')
}

/** 明细：GR/PO 关联链 + 暂估凭证 */
export function getAccrualDetailApi(id) {
  return request.get(`/fin/accruals/${id}`)
}

/**
 * 手工冲回（仅 ADMIN）：OPEN → REVERSED + 六字段留痕 + 红字冲销凭证
 * body = { invoiceNo, reason }
 */
export function reverseAccrualApi(accrualNo, data) {
  return request.post(`/fin/accruals/${accrualNo}/reverse`, data)
}

/** 合并迁移批次财务确认（仅 ADMIN，确认后自动重跑三方匹配 BR-4.1-29） */
export function confirmMigrationApi(batchNo) {
  return request.post(`/fin/accruals/migrations/${encodeURIComponent(batchNo)}/confirm`, {})
}
