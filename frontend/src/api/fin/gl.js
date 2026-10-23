import request from '@/utils/request'

// ================= 8.1.1 科目管理 =================
export const accountPage = (params) => request.get('/fin/accounts', { params })
export const accountCreate = (data) => request.post('/fin/accounts', data)
export const accountSetStatus = (code, status) =>
  request.put(`/fin/accounts/${code}/status`, null, { params: { status } })
export const accountDetail = (code) => request.get(`/fin/accounts/${code}`)

// ================= 8.1.2~8.1.4 手工凭证三态 =================
export const manualPage = (params) => request.get('/fin/manual-vouchers', { params })
export const manualDetail = (id) => request.get(`/fin/manual-vouchers/${id}`)
export const manualCreate = (payload) => request.post('/fin/manual-vouchers', payload)
export const manualAudit = (id) => request.post(`/fin/manual-vouchers/${id}/audit`)
export const manualReject = (id, reason) =>
  request.post(`/fin/manual-vouchers/${id}/reject`, null, { params: { reason } })
export const manualPost = (id) => request.post(`/fin/manual-vouchers/${id}/post`)
export const manualVoid = (id) => request.post(`/fin/manual-vouchers/${id}/void`)
export const manualReverse = (id, reason) =>
  request.post(`/fin/manual-vouchers/${id}/reverse`, null, { params: { reason } })

// ================= 凭证模板 =================
export const templatePage = (params) => request.get('/fin/voucher-templates', { params })
export const templateSave = (data) => request.post('/fin/voucher-templates', data)
export const templateDelete = (id) => request.post(`/fin/voucher-templates/${id}/delete`)
export const templateApply = (id) => request.get(`/fin/voucher-templates/${id}/apply`)

// ================= 余额查询 =================
export const balanceQuery = (params) => request.get('/fin/balances', { params })

// ================= 8.1.5 期末结账 =================
export const periodCurrent = () => request.get('/fin/periods/current')
export const periodList = () => request.get('/fin/periods')
export const periodChecklist = (period) => request.get(`/fin/periods/${period}/checklist`)
export const periodClose = (period) => request.post(`/fin/periods/${period}/close`)
