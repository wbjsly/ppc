import request from '@/utils/request'

// ================= 外币期末重估（M8-5-2/8.5-3，add-fx-revaluation，spec fx-revaluation） =================
/** 试算（不落库）：三类外币货币性项目逐笔差额 + 币种×类型汇总 */
export const trialRun = (period) => request.post('/fin/fx-reval/trial-run', null, { params: { period } })
/** 生成重估批次（上期自动冲回 + 台账 + DRAFT 凭证；同期重复 422） */
export const generateReval = (period) => request.post('/fin/fx-reval/generate', null, { params: { period } })
/** 批次列表（期间倒序：重估额/冲回额/净额/凭证状态） */
export const revalBatches = () => request.get('/fin/fx-reval/batches')
/** 批次明细下钻（币种×类型汇总 + 逐笔 + 凭证链） */
export const revalBatchDetails = (period) => request.get(`/fin/fx-reval/batches/${period}/details`)
/** 汇兑损益看板（6604 科目本期发生额 = 利润表口径） */
export const gainLossSummary = (period) => request.get('/fin/fx-gain-loss/summary', { params: { period } })
