import request from '@/utils/request'

// ================= 调拨（7.4.1 部门 / 7.4.2 法人，同接口 TYPE 区分）=================
export const trfPage = (params) => request.get('/eam/transfers', { params })
export const trfDetail = (id) => request.get(`/eam/transfers/${id}`)
export const trfCreate = (data) => request.post('/eam/transfers', data)
export const trfSubmit = (id) => request.post(`/eam/transfers/${id}/submit`)
export const trfHandover = (id, direction) =>
  request.post(`/eam/transfers/${id}/handover`, null, { params: { direction } })
export const trfComplete = (id) => request.post(`/eam/transfers/${id}/complete`)
export const intercoCheck = () => request.get('/eam/transfers/interco/check')
export const idlePool = () => request.get('/eam/transfers/idle-pool')

// ================= 租赁（7.4.3）=================
export const leasePage = (params) => request.get('/eam/leases', { params })
export const leaseDetail = (id) => request.get(`/eam/leases/${id}`)
export const leaseCreate = (data) => request.post('/eam/leases', data)
export const leaseUpdate = (data) => request.put('/eam/leases', data)
export const leaseDelete = (id) => request.delete(`/eam/leases/${id}`)
export const leaseSuggest = (id) => request.get(`/eam/leases/${id}/suggest`)
export const leaseClassify = (id, params) =>
  request.post(`/eam/leases/${id}/classify`, null, { params })
export const leaseConfirm = (id) => request.post(`/eam/leases/${id}/confirm`)
export const leaseSchedule = (id) => request.get(`/eam/leases/${id}/schedule`)
export const leaseMonthly = (periodTag) => request.post(`/eam/leases/monthly/${periodTag}`)
export const leaseReverse = (periodTag) => request.post(`/eam/leases/reverse/${periodTag}`)
export const leaseRemeasure = (id, params) =>
  request.post(`/eam/leases/${id}/remeasure`, null, { params })
export const leaseExpiry = () => request.get('/eam/leases/expiry')
export const leaseTerminate = (id, params) =>
  request.post(`/eam/leases/${id}/terminate`, null, { params })
