import request from '@/utils/request'

/**
 * 齐套检查（5.5，change add-kitting-check，spec kitting-check）。
 * 5.5.1 校验工作台 / 5.5.2 缺料预警聚合 + 缺料转采购建议（5.3.1 单一漏斗）。
 */

/** 批量齐套校验（串行去重 + 人工源静默；单张传一个 ID） */
export function checkKittingApi(moIds) {
  return request.post('/mrp/kitting/checks', { moIds })
}

/** 齐套逐行明细（只读现算 + 缺料行替代候选提示，不参与判定） */
export function getKittingDetailApi(moId) {
  return request.get(`/mrp/mos/${moId}/kitting-detail`)
}

/** 缺料聚合看板（按物料 group：缺料工单数/总缺料量/明细下钻） */
export function getShortageAggregateApi(params) {
  return request.get('/mrp/kitting/shortages', { params })
}

/** 缺料行 → 采购建议（PENDING，5.3.1 审核转正；未处置重复转 422） */
export function convertFromShortageApi(shortageIds) {
  return request.post('/mrp/suggestions/from-shortage', { shortageIds })
}

/** 工单列表（复用 5.4：status/shortageFlag 筛选） */
export function getKittingMosApi(params) {
  return request.get('/mrp/mos', { params })
}
