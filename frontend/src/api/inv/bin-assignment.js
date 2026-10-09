import request from '@/utils/request'

/** Tab A：待分配 GR 行（CREATED × 检验放行 × 核销量>0，含分配状态聚合） */
export function getPendingGrLinesApi(params) {
  return request.get('/inv/bin-assignment/pending-gr-lines', { params })
}

/** 查看建议：该 GR 行 Top3 候选（不落库） */
export function getCandidatesApi(grNo, lineNo) {
  return request.get('/inv/bin-assignment/candidates', { params: { grNo, lineNo } })
}

/** 推荐（RECOMMENDED；无候选挂起 + 通知） */
export function recommendApi(grNo, lineNo) {
  return request.post('/inv/bin-assignment/recommend', null, { params: { grNo, lineNo } })
}

/** 一键推荐全部并确认（异常行挂起不阻断） */
export function recommendAllApi() {
  return request.post('/inv/bin-assignment/recommend-all')
}

/** 确认分配（STOCK 来源同时执行上架移位） */
export function confirmAssignApi(id) {
  return request.post(`/inv/bin-assignment/${id}/confirm`)
}

/** 指定/改派（合规复检不豁免，违规 422） */
export function assignApi(grNo, lineNo, binCode) {
  return request.post('/inv/bin-assignment/assign', null, { params: { grNo, lineNo, binCode } })
}

/** Tab B：未分配（BIN_CODE=''）库存行 */
export function getUnassignedApi(params) {
  return request.get('/inv/bin-assignment/unassigned-stock', { params })
}

/** Tab B：某未分配行的上架候选 Top3 */
export function getPutawayCandidatesApi(stockId) {
  return request.get('/inv/bin-assignment/putaway-candidates', { params: { stockId } })
}

/** Tab B：推荐上架（RECOMMENDED；无候选挂起 + 通知） */
export function recommendPutawayApi(stockId) {
  return request.post('/inv/bin-assignment/recommend-putaway', null, { params: { stockId } })
}

/** 台账历史（含被替代记录，全留痕） */
export function getHistoryApi(sourceDocNo) {
  return request.get('/inv/bin-assignment/history', { params: { sourceDocNo } })
}
