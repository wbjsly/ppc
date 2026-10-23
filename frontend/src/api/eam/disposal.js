import request from '@/utils/request'

// 处置单分页（7.6.1~7.6.3 共用；type=SCRAP|SALE，status 见状态机）
export const disposalPage = (params) => request.get('/eam/disposals', { params })
export const disposalDetail = (id) => request.get(`/eam/disposals/${id}`)
export const disposalCreate = (payload) => request.post('/eam/disposals', payload)
export const disposalUpdate = (id, payload) => request.put(`/eam/disposals/${id}`, payload)
export const disposalSubmit = (id) => request.post(`/eam/disposals/${id}/submit`)
export const disposalVoid = (id, reason) =>
  request.post(`/eam/disposals/${id}/void`, null, { params: { reason } })
export const disposalExec = (id, params) => request.post(`/eam/disposals/${id}/exec`, null, { params })
export const disposalReceive = (id, params) => request.post(`/eam/disposals/${id}/receive`, null, { params })
export const disposalSubmitReport = (id) => request.post(`/eam/disposals/${id}/submit-report`)
export const disposalReverse = (id) => request.post(`/eam/disposals/${id}/reverse`)
export const disposalIdlePool = () => request.get('/eam/disposals/idle-pool')

// 卡片选择（批量选卡反查）
export const cardSearch = (keyword) =>
  request.get('/eam/cards', { params: { current: 1, size: 10, keyword } })
