import request from '@/utils/request'

// ================= 统一收付款中心（M8-4-1/8.4-2，spec payment-collection） =================
/** 收付款单分页（方向/状态/往来对象/关键字/日期区间） */
export const cashDocPage = (params) => request.get('/fin/cash-docs', { params })
/** 收付款单详情（含核销明细） */
export const cashDocDetail = (id) => request.get(`/fin/cash-docs/${id}`)
/** 创建收付款单（IN 收款 / OUT 付款） */
export const createCashDoc = (data) => request.post('/fin/cash-docs', data)
/** 过账（余额 + 资金凭证 + FIFO 核销，跨期挂审批） */
export const postCashDoc = (id) => request.post(`/fin/cash-docs/${id}/post`)
/** 作废（仅草稿/待过账） */
export const voidCashDoc = (id, reason) => request.post(`/fin/cash-docs/${id}/void`, { reason })
/** 付款执行（排期申请 → OUT 单创建+过账，旧 API 兼容形态） */
export const executeOut = (data) => request.post('/fin/cash-docs/execute-out', data)
/** 人工核销（手动指定目标单据；跨期挂审批） */
export const manualSettle = (id, data) => request.post(`/fin/cash-docs/${id}/manual-settle`, data)
/** 单据核销明细 */
export const cashDocWriteoffs = (id) => request.get(`/fin/cash-docs/${id}/writeoffs`)

// ================= 回单对账（M8-4-3，spec bank-receipt-reconciliation） =================
/** 回单分页（匹配状态/关键字/日期区间） */
export const receiptPage = (params) => request.get('/fin/bank-receipts', { params })
/** 回单详情（含匹配单据） */
export const receiptDetail = (id) => request.get(`/fin/bank-receipts/${id}`)
/** 手工录入回单 */
export const createReceipt = (data) => request.post('/fin/bank-receipts', data)
/** CSV 批量导入（逐行报告） */
export const importReceipts = (csv) => request.post('/fin/bank-receipts/import', { csv })
/** 自动匹配（账户+方向+金额±3 天，一对一） */
export const autoMatch = () => request.post('/fin/bank-receipts/auto-match')
/** 人工匹配（方向/账户校验） */
export const manualMatch = (id, docId) => request.post(`/fin/bank-receipts/${id}/match`, { docId })
/** 标记异常 */
export const markReceiptException = (id, reason) =>
  request.post(`/fin/bank-receipts/${id}/exception`, { reason })
/** 解除匹配（回单回 UNMATCHED、单据回 POSTED） */
export const unreconcile = (id, reason) =>
  request.post(`/fin/bank-receipts/${id}/unreconcile`, { reason })
