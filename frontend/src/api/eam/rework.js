import request from '@/utils/request'

// 技改大修单（7.7.1 资本确认；状态 DRAFT/PENDING_JUDGE/JUDGED/IN_REWORK/COMPLETED/REBUILT/EXPENSED/CLOSED）
export const reworkPage = (params) => request.get('/eam/reworks', { params })
export const reworkDetail = (id) => request.get(`/eam/reworks/${id}`)
export const reworkCreate = (payload) => request.post('/eam/reworks', payload)
export const reworkUpdate = (id, payload) => request.put(`/eam/reworks/${id}`, payload)
export const reworkSubmit = (id) => request.post(`/eam/reworks/${id}/submit`)
export const reworkJudge = (id, params) => request.post(`/eam/reworks/${id}/judge`, null, { params })
export const reworkArbitrate = (id, note) =>
  request.post(`/eam/reworks/${id}/arbitrate`, null, { params: { note } })
export const reworkContinueDepr = (id, reason) =>
  request.post(`/eam/reworks/${id}/continue-depr`, null, { params: { reason } })
export const reworkToInRework = (id) => request.post(`/eam/reworks/${id}/to-in-rework`)
export const reworkAccept = (id, acceptDate) =>
  request.post(`/eam/reworks/${id}/accept`, null, { params: { acceptDate } })
export const reworkRebuild = (id, newLifeYears) =>
  request.post(`/eam/reworks/${id}/rebuild`, null, { params: { newLifeYears } })
export const reworkExpense = (id) => request.post(`/eam/reworks/${id}/expense`)
export const reworkClose = (id) => request.post(`/eam/reworks/${id}/close`)
export const reworkReverse = (id) => request.post(`/eam/reworks/${id}/reverse`)
export const reworkOverdueScan = () => request.post('/eam/reworks/overdue-scan')

// 卡片选择（发起技改选原卡）
export const reworkCardSearch = (keyword) =>
  request.get('/eam/cards', { params: { current: 1, size: 10, keyword } })
