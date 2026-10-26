import request from '@/utils/request'

// ---------- 班次与排班（10.3.1） ----------
export function shiftList() {
  return request({ url: '/api/hr/attendance/shifts', method: 'get' })
}

export function createShift(data) {
  return request({ url: '/api/hr/attendance/shifts', method: 'post', data })
}

export function updateShift(id, data) {
  return request({ url: `/api/hr/attendance/shifts/${id}`, method: 'put', data })
}

export function toggleShift(id, status) {
  return request({ url: `/api/hr/attendance/shifts/${id}/status`, method: 'put', params: { status } })
}

export function schedule(data) {
  return request({ url: '/api/hr/attendance/schedules', method: 'post', data })
}

export function bulkSchedule(data) {
  return request({ url: '/api/hr/attendance/schedules/bulk', method: 'post', data })
}

export function copyMonth(from, to) {
  return request({ url: '/api/hr/attendance/schedules/copy', method: 'post', params: { from, to } })
}

export function deptMatrix(params) {
  return request({ url: '/api/hr/attendance/schedules', method: 'get', params })
}

export function mySchedules(params) {
  return request({ url: '/api/hr/attendance/schedules/my', method: 'get', params })
}

// ---------- 打卡（10.3.2 顶部） ----------
export function webPunch(data) {
  return request({ url: '/api/hr/attendance/punches/web', method: 'post', data })
}

export function gpsPunch(data) {
  return request({ url: '/api/hr/attendance/punches/gps', method: 'post', data })
}

export function importPunchCsv(rows) {
  return request({ url: '/api/hr/attendance/punches/import', method: 'post', data: rows })
}

export function myPunches(params) {
  return request({ url: '/api/hr/attendance/punches/my', method: 'get', params })
}

// ---------- 异常状态机（10.3.2~5） ----------
export function exceptionList(params) {
  return request({ url: '/api/hr/attendance/exceptions', method: 'get', params })
}

export function myExceptions(params) {
  return request({ url: '/api/hr/attendance/exceptions/my', method: 'get', params })
}

export function batchAppeal(data) {
  return request({ url: '/api/hr/attendance/exceptions/appeal', method: 'post', data })
}

export function returnAppeal(data) {
  return request({ url: '/api/hr/attendance/exceptions/return', method: 'post', data })
}

export function mgrConfirm(data) {
  return request({ url: '/api/hr/attendance/exceptions/mgr-confirm', method: 'post', data })
}

export function finalize(data) {
  return request({ url: '/api/hr/attendance/exceptions/finalize', method: 'post', data })
}

export function correct(data) {
  return request({ url: '/api/hr/attendance/exceptions/correct', method: 'post', data })
}

export function pendingConfirmations() {
  return request({ url: '/api/hr/attendance/pending-confirmations', method: 'get' })
}

export function attendanceSummary(period) {
  return request({ url: '/api/hr/attendance/summary', method: 'get', params: { period } })
}

export function manualScan(date) {
  return request({ url: '/api/hr/attendance/scan', method: 'post', params: { date } })
}

// ---------- 补卡 ----------
export function submitMakeup(data) {
  return request({ url: '/api/hr/attendance/makeups', method: 'post', data })
}

export function mgrConfirmMakeup(id, data) {
  return request({ url: `/api/hr/attendance/makeups/${id}/mgr-confirm`, method: 'post', data })
}

export function hrReviewMakeup(id, data) {
  return request({ url: `/api/hr/attendance/makeups/${id}/hr-review`, method: 'post', data })
}

export function makeupList(params) {
  return request({ url: '/api/hr/attendance/makeups', method: 'get', params })
}

// ---------- 加班（10.4.1/3） ----------
export function otSubmit(data) {
  return request({ url: '/api/hr/overtime/applies', method: 'post', data })
}

export function otApprove(id, data) {
  return request({ url: `/api/hr/overtime/applies/${id}/approve`, method: 'post', data })
}

export function otApprovalView(id) {
  return request({ url: `/api/hr/overtime/applies/${id}/approval-view`, method: 'get' })
}

export function otApplyList(params) {
  return request({ url: '/api/hr/overtime/applies', method: 'get', params })
}

export function otApprovalReminders() {
  return request({ url: '/api/hr/overtime/approval-reminders', method: 'get' })
}

export function otConfirmScan() {
  return request({ url: '/api/hr/overtime/confirms/scan', method: 'post' })
}

export function otResolveExcess(id, data) {
  return request({ url: `/api/hr/overtime/confirms/${id}/resolve-excess`, method: 'post', data })
}

export function otSplit(id, data) {
  return request({ url: `/api/hr/overtime/confirms/${id}/split`, method: 'post', data })
}

export function otTrial(id) {
  return request({ url: `/api/hr/overtime/confirms/${id}/trial`, method: 'get' })
}

export function otConfirmList(params) {
  return request({ url: '/api/hr/overtime/confirms', method: 'get', params })
}

// ---------- 调休额度（10.4.2） ----------
export function compTimeBatches(params) {
  return request({ url: '/api/hr/overtime/comp-time/batches', method: 'get', params })
}

export function compTimeBalance(params) {
  return request({ url: '/api/hr/overtime/comp-time/balance', method: 'get', params })
}

export function compTimeExpiryScan() {
  return request({ url: '/api/hr/overtime/comp-time/expiry-scan', method: 'post' })
}

export function compTimeResignSettlement(empId) {
  return request({ url: '/api/hr/overtime/comp-time/resign-settlement', method: 'get', params: { empId } })
}

// ---------- 请假（10.4.4） ----------
export function leaveTypes() {
  return request({ url: '/api/hr/leave/types', method: 'get' })
}

export function updateLeaveType(typeCode, data) {
  return request({ url: `/api/hr/leave/types/${typeCode}`, method: 'put', data })
}

export function leaveBalances(params) {
  return request({ url: '/api/hr/leave/balances', method: 'get', params })
}

export function annualGrantScan() {
  return request({ url: '/api/hr/leave/balances/annual-grant-scan', method: 'post' })
}

export function annualExpireScan() {
  return request({ url: '/api/hr/leave/balances/annual-expire-scan', method: 'post' })
}

export function leaveSubmit(data) {
  return request({ url: '/api/hr/leave/applies', method: 'post', data })
}

export function leaveApprove(id, data) {
  return request({ url: `/api/hr/leave/applies/${id}/approve`, method: 'post', data })
}

export function leaveCancel(id, reason) {
  return request({ url: `/api/hr/leave/applies/${id}/cancel`, method: 'post', params: { reason } })
}

export function leaveApplyList(params) {
  return request({ url: '/api/hr/leave/applies', method: 'get', params })
}
