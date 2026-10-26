import request from '@/utils/request'

// ============ 10.1.1/10.1.2 员工档案（employee-archive） ============

export function deptList() {
  return request({ url: '/api/hr/depts', method: 'get' })
}
export function deptCreate(data) {
  return request({ url: '/api/hr/depts', method: 'post', data })
}
export function deptToggle(id, status) {
  return request({ url: `/api/hr/depts/${id}/toggle`, method: 'put', data: { status } })
}
export function posList(params) {
  return request({ url: '/api/hr/positions', method: 'get', params })
}
export function posCreate(data) {
  return request({ url: '/api/hr/positions', method: 'post', data })
}
export function posToggle(id, status) {
  return request({ url: `/api/hr/positions/${id}/toggle`, method: 'put', data: { status } })
}

export function onbCreate(data) {
  return request({ url: '/api/hr/onboardings', method: 'post', data })
}
export function onbAdvance(id, data) {
  return request({ url: `/api/hr/onboardings/${id}/advance`, method: 'post', data })
}
export function onbReturn(id, reason) {
  return request({ url: `/api/hr/onboardings/${id}/return`, method: 'post', data: { reason } })
}
export function onbList(params) {
  return request({ url: '/api/hr/onboardings', method: 'get', params })
}
export function onbDetail(id) {
  return request({ url: `/api/hr/onboardings/${id}`, method: 'get' })
}

export function empList(params) {
  return request({ url: '/api/hr/employees', method: 'get', params })
}
export function empDetail(id) {
  return request({ url: `/api/hr/employees/${id}`, method: 'get' })
}
export function empUpdate(id, data) {
  return request({ url: `/api/hr/employees/${id}`, method: 'put', data })
}
export function empLockChange(id, data) {
  return request({ url: `/api/hr/employees/${id}/lock-change`, method: 'post', data })
}
export function empChangeLogs(id) {
  return request({ url: `/api/hr/employees/${id}/change-logs`, method: 'get' })
}
export function probationRemindings() {
  return request({ url: '/api/hr/probation-remindings', method: 'get' })
}
export function rehireProbe(idCardNo) {
  return request({ url: '/api/hr/rehire-probe', method: 'get', params: { idCardNo } })
}

// ============ 10.7 入转调离（employee-transfer） ============

export function trfList(params) {
  return request({ url: '/api/hr/transfers', method: 'get', params })
}
export function trfDetail(id) {
  return request({ url: `/api/hr/transfers/${id}`, method: 'get' })
}
export function probationSubmit(data) {
  return request({ url: '/api/hr/transfers/probation', method: 'post', data })
}
export function probationHrReview(id, opinion) {
  return request({ url: `/api/hr/transfers/probation/${id}/hr-review`, method: 'post', data: { opinion } })
}
export function postSubmit(data) {
  return request({ url: '/api/hr/transfers/post', method: 'post', data })
}
export function postConfirmOut(id, opinion) {
  return request({ url: `/api/hr/transfers/post/${id}/confirm-out`, method: 'post', data: { opinion } })
}
export function postConfirmIn(id, opinion) {
  return request({ url: `/api/hr/transfers/post/${id}/confirm-in`, method: 'post', data: { opinion } })
}
export function postHrReview(id, opinion) {
  return request({ url: `/api/hr/transfers/post/${id}/hr-review`, method: 'post', data: { opinion } })
}
export function postReviewSign(id, data) {
  return request({ url: `/api/hr/transfers/post/${id}/review-sign`, method: 'post', data })
}
export function postAdjudicate(id, data) {
  return request({ url: `/api/hr/transfers/post/${id}/adjudicate`, method: 'post', data })
}

export function exitApply(data) {
  return request({ url: '/api/hr/transfers/exits', method: 'post', data })
}
export function exitStartApproval(id) {
  return request({ url: `/api/hr/transfers/exits/${id}/start-approval`, method: 'post' })
}
export function exitChecklist(id) {
  return request({ url: `/api/hr/transfers/exits/${id}/checklist`, method: 'get' })
}
export function exitAssetsReturn(id, cardIds) {
  return request({ url: `/api/hr/transfers/exits/${id}/assets-return`, method: 'post', data: { cardIds } })
}
export function exitAccountHandover(id) {
  return request({ url: `/api/hr/transfers/exits/${id}/account-handover`, method: 'post' })
}
export function exitHandoverItems(id, category, items) {
  return request({ url: `/api/hr/transfers/exits/${id}/handover-items`, method: 'post', data: { category, items } })
}
export function exitSettle(id, data) {
  return request({ url: `/api/hr/transfers/exits/${id}/settle`, method: 'post', data })
}
export function exitIssueCert(id) {
  return request({ url: `/api/hr/transfers/exits/${id}/issue-cert`, method: 'post' })
}
export function exitList(params) {
  return request({ url: '/api/hr/transfers/exits', method: 'get', params })
}
export function exitDetail(id) {
  return request({ url: `/api/hr/transfers/exits/${id}`, method: 'get' })
}

// ---------- add-transfer-followup（入转调离缺口补齐） ----------

export function postPlaceChange(id, placeChange, newPlace) {
  return request({ url: `/api/hr/transfers/post/${id}/place-change`, method: 'post', data: { placeChange, newPlace } })
}
export function exitRetainNote(id, note) {
  return request({ url: `/api/hr/transfers/exits/${id}/retain-note`, method: 'post', data: { note } })
}
export function exitWaiveReduce(id, reason) {
  return request({ url: `/api/hr/transfers/exits/${id}/waive-reduce`, method: 'post', data: { reason } })
}

// 员工自助端离职（/api/my/exit）
export function myExitApply(data) {
  return request({ url: '/api/my/exit', method: 'post', data })
}
export function myExitList() {
  return request({ url: '/api/my/exit', method: 'get' })
}
export function myExitDetail(id) {
  return request({ url: `/api/my/exit/${id}`, method: 'get' })
}
