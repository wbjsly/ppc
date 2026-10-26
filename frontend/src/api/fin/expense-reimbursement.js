import request from '@/utils/request'

// ================= 费用报销与借款（M8-8，add-expense-reimbursement，spec expense-reimbursement） =================
// --- 报销（8.8.1） ---
/** 提交报销单（头+行；发票重复 L1 展示原单号、映射缺失 422） */
export const submitReimburse = (payload) => request.post('/fin/reimburse', payload)
/** 报销单列表（状态/员工过滤） */
export const listReimburses = (params) => request.get('/fin/reimburse', { params })
/** 报销单详情（行+冲销明细+结算信息） */
export const reimburseDetail = (id) => request.get(`/fin/reimburse/${id}`)
/** 待结算差额计算 */
export const settleInfo = (id) => request.get(`/fin/reimburse/${id}/settle-info`)
/** 出纳补付差额（收付中心 OUT，余额不足 422） */
export const payDifference = (id, payload) => request.post(`/fin/reimburse/${id}/pay-difference`, payload)
/** 登记员工退款（收付中心 IN，回写借款余额） */
export const refundLoan = (id, payload) => request.post(`/fin/reimburse/${id}/refund`, payload)

// --- 费用类型映射（8.8.1 维护抽屉） ---
export const listExpenseTypes = () => request.get('/fin/reimburse/expense-types')
export const saveExpenseType = (payload) => request.post('/fin/reimburse/expense-types', payload)
export const updateExpenseTypeStatus = (id, status) =>
  request.post(`/fin/reimburse/expense-types/${id}/status`, { status })

// --- 借款（8.8.2/8.8.3） ---
/** 提交借款申请（超期>90天自动加签财务主管，C-4.6-14） */
export const applyLoan = (payload) => request.post('/fin/employee-loans', payload)
export const listLoans = (params) => request.get('/fin/employee-loans', { params })
export const loanDetail = (id) => request.get(`/fin/employee-loans/${id}`)
/** 账龄监控视图（按员工聚合，>90天红标） */
export const loanAgingView = () => request.get('/fin/employee-loans/aging-view')
