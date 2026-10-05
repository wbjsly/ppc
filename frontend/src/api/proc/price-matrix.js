import request from '@/utils/request'

// ---------- 比价矩阵工作台（2.2.4，change add-price-comparison-matrix） ----------

/** 可比价品类选项（有 RFQ 行的品类） */
export function getMatrixCategoriesApi() {
  return request.get('/proc/price-matrix/categories')
}

/** 品类视角：多供应商跨 RFQ 横比 */
export function getCategoryViewApi(categoryCode) {
  return request.get('/proc/price-matrix/category', { params: { categoryCode } })
}

/** 供应商视角：同品类各 RFQ 报价明细 + 汇总 */
export function getSupplierViewApi(categoryCode, supplierId) {
  return request.get('/proc/price-matrix/supplier', {
    params: { categoryCode, supplierId }
  })
}

/** 比价记录列表（快照冗余列） */
export function getSnapshotsApi() {
  return request.get('/proc/price-matrix/snapshots')
}

/** 快照回看（按 RFQ 解析 JSON；未定标 404） */
export function getSnapshotApi(rfqId) {
  return request.get(`/proc/price-matrix/snapshots/${rfqId}`)
}
