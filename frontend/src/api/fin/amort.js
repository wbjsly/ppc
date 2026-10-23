import request from '@/utils/request'

// ================= 预提待摊（M8-9，add-expense-reimbursement，spec prepaid-amortization） =================
/** 预提登记（生成单期计提计划，L1 合计校验） */
export const registerPrepaid = (payload) => request.post('/fin/amort/prepaid', payload)
/** 待摊登记（直线法尾差并入末期；>12月 L4 提示） */
export const registerDeferred = (payload) => request.post('/fin/amort/deferred', payload)
/** 登记单列表（type=PREPAY|AMORT，含计划进度） */
export const listAmortDocs = (type) => request.get('/fin/amort/docs', { params: { type } })
/** 单据详情 + 计划行下钻 */
export const amortDocDetail = (id) => request.get(`/fin/amort/docs/${id}`)
/** 生成本期计提/摊销凭证（幂等；DRAFT 待审核，FR-4.6-8-3/4） */
export const generateAmort = (period) => request.post('/fin/amort/generate', null, { params: { period } })
/** 预提实际冲销（差异>0.5% 摘要标记，C-4.6-16） */
export const settlePrepaid = (id, payload) => request.post(`/fin/amort/prepaid/${id}/settle`, payload)
/** 年末余额检查（L4 黄标，FR-4.6-8-6） */
export const yearEndCheck = (period) => request.get('/fin/amort/year-end-check', { params: { period } })
