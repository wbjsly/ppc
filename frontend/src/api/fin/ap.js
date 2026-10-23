import request from '@/utils/request'

// ================= 8.3.2 应付账龄 =================
/** 应付账龄六段分析（复用 AR_AGING_BUCKETS，基数=POSTED 且未清>0） */
export const apAging = (params) => request.get('/fin/ap-mgmt/aging', { params })
/** 存量发票到期日批量回填 */
export const backfillDueDate = () => request.post('/fin/ap-mgmt/aging/backfill-due-date')
/** 存量发票到期日批量回填 */

// ================= 8.3.3 资金预测 =================
/** 资金需求预测（滚动 7/30/90 天：未清应付+已排期+预付占用−银行余额→缺口） */
export const apForecast = (params) => request.get('/fin/ap-mgmt/forecast', { params })

// ================= 8.3.3 付款计划闭环 =================
/** 创建付款计划（PERIOD+法人唯一） */
export const createPlan = (data) => request.post('/fin/ap-mgmt/plans', data)
/** 提交计划审批 */
export const submitPlan = (id) => request.post(`/fin/ap-mgmt/plans/${id}/submit`)
/** 审批回调（通过/驳回留因） */
export const planApproval = (id, approved, reason) =>
  request.post(`/fin/ap-mgmt/plans/${id}/approval`, null, { params: { approved, reason } })
/** 关闭计划（期末归档） */
export const closePlan = (id) => request.post(`/fin/ap-mgmt/plans/${id}/close`)
/** 计划分页 */
export const planPage = (params) => request.get('/fin/ap-mgmt/plans', { params })
/** 计划详情（分供应商行 JSON 解析） */
export const planDetail = (id) => request.get(`/fin/ap-mgmt/plans/${id}`)

// ================= 8.3.1 应付确认（复用既有 FinInvoiceController 三方匹配端点） =================
/** 匹配台账（状态/供应商/发票号/PO 筛选） */
export const matchPage = (params) => request.get('/fin/invoices/matches', { params })
/** 匹配单详情（逐行差异明细 + 凭证） */
export const matchDetail = (id) => request.get(`/fin/invoices/matches/${id}`)
/** 执行三单匹配（容差内自动冲回暂估 + 转正式应付；超容差冻结生成异常对账单） */
export const runMatch = (invoiceId) => request.post(`/fin/invoices/${invoiceId}/match`)
/** 采购员确认价差（必填意见；非跨期确认即过账，跨期待手工过账） */
export const confirmMatch = (matchId, opinion) =>
  request.post(`/fin/invoices/matches/${matchId}/confirm`, null, { params: { opinion } })
/** 跨期手工过账（仅 ADMIN，生成当期价差调整凭证并注明跨期原因） */
export const manualPostMatch = (matchId, note) =>
  request.post(`/fin/invoices/matches/${matchId}/manual-post`, null, { params: { note } })

// ================= 8.3.2 应付核销（复用既有对账单/核销端点） =================
/** 供应商对账单分页 */
export const statementPage = (params) => request.get('/fin/statements', { params })
/** 对账单详情 */
export const statementDetail = (id) => request.get(`/fin/statements/${id}`)
/** 对账单匹配 */
export const matchStatement = (id, data) => request.post(`/fin/statements/${id}/match`, data)
/** 对账单确认 */
export const confirmStatement = (id) => request.post(`/fin/statements/${id}/confirm`)
/** 核销记录分页（KIND=PAYMENT/SETTLE 通用） */
export const writeoffPage = (params) => request.get('/fin/payments/writeoffs', { params })
