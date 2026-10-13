import request from '@/utils/request'

/** 判定来源候选（srcType: NCR=处置返工 / RETURN=判定可返修；未关联返工工单） */
export function getSourcesApi(srcType) {
  return request.get('/mrp/rework/sources', { params: { srcType } })
}

/** 创建返工工单（head + bom 追加料清单，可空） */
export function createReworkApi(head, bom) {
  return request.post('/mrp/rework/create', { head, bom: bom || [] })
}

/** 返工工单列表（RW 分池） */
export function getReworkListApi(params) {
  return request.get('/mrp/rework/list', { params })
}

/** 阶段推导（PENDING_INSP / PENDING_STOCK_IN / CLOSED） */
export function getReworkPhaseApi(moId) {
  return request.get(`/mrp/rework/phase/${moId}`)
}

/** 返工成本归集明细（COPQ REWORK 行） */
export function getReworkCostApi(moId) {
  return request.get(`/mrp/rework/cost/${moId}`)
}

/** 发起二次返工审批（质量侧） */
export function applySecondApi(srcNo) {
  return request.post('/mrp/rework/second', { srcNo })
}

/** 强制报废执行（BR-4.5-02 第二次返工检验不合格） */
export function enforceScrapApi(moId) {
  return request.post('/mrp/rework/scrap', { moId })
}

/** 复检合格入库解冻关单（待入库重试，幂等） */
export function stockInApi(moId) {
  return request.post('/mrp/rework/stock-in', { moId })
}
