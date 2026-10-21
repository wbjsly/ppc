import request from '@/utils/request'

// ================= 维护计划（7.3.1）=================
export const planPage = (params) => request.get('/eam/maint-plans', { params })
export const savePlan = (data) => request.post('/eam/maint-plans', data)
export const submitPlanApproval = (id) => request.post(`/eam/maint-plans/${id}/submit-approval`)
export const disablePlan = (id) => request.post(`/eam/maint-plans/${id}/disable`)
export const generatePlanOrders = () => request.post('/eam/maint-plans/generate-orders')

// ================= 工单（7.3.1/7.3.2）=================
export const woPage = (params) => request.get('/eam/work-orders', { params })
export const woDetail = (woNo) => request.get(`/eam/work-orders/${woNo}`)
export const woAssign = (woNo, assignee) =>
  request.post(`/eam/work-orders/${woNo}/assign`, null, { params: { assignee } })
export const woStart = (woNo) => request.post(`/eam/work-orders/${woNo}/start`)
export const woExecution = (woNo, data) => request.post(`/eam/work-orders/${woNo}/execution`, data)
export const woSubWorkOrder = (woNo, data) =>
  request.post(`/eam/work-orders/${woNo}/sub-work-orders`, data)
export const woSubmitAcceptance = (woNo) => request.post(`/eam/work-orders/${woNo}/submit-acceptance`)
export const woAccept = (woNo, who, resultEval) =>
  request.post(`/eam/work-orders/${woNo}/accept`, null, { params: { who, resultEval } })
export const woClose = (woNo) => request.post(`/eam/work-orders/${woNo}/close`)
export const woCancel = (woNo, reason) =>
  request.post(`/eam/work-orders/${woNo}/cancel`, null, { params: { reason } })
export const woSpares = (woNo, data) => request.post(`/eam/work-orders/${woNo}/spares`, data)

// ================= 报修（7.3.2，全员可提）=================
export const reportPage = (params) => request.get('/eam/repair-reports', { params })
export const submitReport = (data) => request.post('/eam/repair-reports', data)
export const convertReport = (id, params) => request.post(`/eam/repair-reports/${id}/convert`, null, { params })
export const rejectReport = (id, reason) =>
  request.post(`/eam/repair-reports/${id}/reject`, null, { params: { reason } })

// ================= 安全停机挂锁 =================
export const lockPage = (params) => request.get('/eam/safety-locks', { params })
export const lockAsset = (params) => request.post('/eam/safety-locks', null, { params })
export const releaseLock = (id, releaseReason) =>
  request.post(`/eam/safety-locks/${id}/release`, null, { params: { releaseReason } })

// ================= 履历与分析 =================
export const maintenanceHistory = (cardId) =>
  request.get(`/eam/work-orders/maintenance-history/${cardId}`)
export const maintenanceStats = (cardId, months = 12) =>
  request.get('/eam/work-orders/stats', { params: { cardId, months } })
