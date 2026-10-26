import request from '@/utils/request'

// ---------- 社保扣缴（10.5.1） ----------
export function policyList(params) {
  return request({ url: '/api/hr/payroll/social/policies', method: 'get', params })
}

export function savePolicy(data) {
  return request({ url: '/api/hr/payroll/social/policies', method: 'post', data })
}

export function copyPolicyYear(data) {
  return request({ url: '/api/hr/payroll/social/policies/copy-year', method: 'post', data })
}

export function socialCalc(empId, year) {
  return request({ url: '/api/hr/payroll/social/calc', method: 'get', params: { empId, year } })
}

export function julyScan() {
  return request({ url: '/api/hr/payroll/social/july-scan', method: 'post' })
}

// ---------- 个税（10.5.2） ----------
export function iitTaxTable(year) {
  return request({ url: '/api/hr/payroll/iit/tax-table', method: 'get', params: { year } })
}

export function iitCumulative(params) {
  return request({ url: '/api/hr/payroll/iit/cumulative', method: 'get', params })
}

export function iitSpecialList(empId) {
  return request({ url: '/api/hr/payroll/iit/special-deductions', method: 'get', params: { empId } })
}

export function saveSpecialDeduction(data) {
  return request({ url: '/api/hr/payroll/iit/special-deductions', method: 'post', data })
}

export function bonusTaxCalc(data) {
  return request({ url: '/api/hr/payroll/iit/bonus-calc', method: 'post', data })
}

// ---------- 薪资核算与 PayrollRun（10.5.3/10.5.4） ----------
export function payAdjustmentList(params) {
  return request({ url: '/api/hr/payroll/runs/adjustments', method: 'get', params })
}

export function savePayAdjustment(data) {
  return request({ url: '/api/hr/payroll/runs/adjustments', method: 'post', data })
}

export function payPreview(period) {
  return request({ url: '/api/hr/payroll/runs/preview', method: 'get', params: { period } })
}

export function payGenerate(period, data) {
  return request({ url: `/api/hr/payroll/runs/generate`, method: 'post', params: { period }, data: data || {} })
}

export function paySubmit(id, data) {
  return request({ url: `/api/hr/payroll/runs/${id}/submit`, method: 'post', data })
}

export function payApprove(id, data) {
  return request({ url: `/api/hr/payroll/runs/${id}/approve`, method: 'post', data })
}

export function payVoid(id, data) {
  return request({ url: `/api/hr/payroll/runs/${id}/void`, method: 'post', data })
}

export function payRunList(params) {
  return request({ url: '/api/hr/payroll/runs', method: 'get', params })
}

export function payRunItems(id) {
  return request({ url: `/api/hr/payroll/runs/${id}/items`, method: 'get' })
}

export function paySubmitCorrection(data) {
  return request({ url: '/api/hr/payroll/runs/corrections', method: 'post', data })
}

export function payApproveCorrection(id, data) {
  return request({ url: `/api/hr/payroll/runs/corrections/${id}/approve`, method: 'post', data })
}

export function payCorrectionList(params) {
  return request({ url: '/api/hr/payroll/runs/corrections', method: 'get', params })
}

export function payReplyDispute(id, content) {
  return request({ url: `/api/hr/payroll/runs/payslips/${id}/reply`, method: 'post', data: { content } })
}

// ---------- 薪酬发放与对账（10.6） ----------
export function disbFileList(params) {
  return request({ url: '/api/hr/payroll/disbursement/files', method: 'get', params })
}

export function disbGenerateFile(data) {
  return request({ url: '/api/hr/payroll/disbursement/files', method: 'post', data })
}

export function disbVoidFile(id, data) {
  return request({ url: `/api/hr/payroll/disbursement/files/${id}/void`, method: 'post', data })
}

export function disbRegisterExec(data) {
  return request({ url: '/api/hr/payroll/disbursement/executions', method: 'post', data })
}

export function disbExecList(runId) {
  return request({ url: '/api/hr/payroll/disbursement/executions', method: 'get', params: { runId } })
}

export function disbRegisterReceipt(data) {
  return request({ url: '/api/hr/payroll/disbursement/receipts', method: 'post', data })
}

export function disbReconcile(data) {
  return request({ url: '/api/hr/payroll/disbursement/reconcile', method: 'post', data })
}

export function disbDiffList(params) {
  return request({ url: '/api/hr/payroll/disbursement/diffs', method: 'get', params })
}

export function disbResolveDiff(id, data) {
  return request({ url: `/api/hr/payroll/disbursement/diffs/${id}/resolve`, method: 'post', data })
}

export function disbApproveDiff(id, data) {
  return request({ url: `/api/hr/payroll/disbursement/diffs/${id}/approve`, method: 'post', data })
}

export function disbArchive(data) {
  return request({ url: '/api/hr/payroll/disbursement/archive', method: 'post', data })
}

export function disbReconBoard(runId) {
  return request({ url: '/api/hr/payroll/disbursement/recon-board', method: 'get', params: { runId } })
}

// ---------- social-insurance-ops（社保增减员单据） ----------

export function socialChangeList(params) {
  return request({ url: '/api/hr/social-changes', method: 'get', params })
}
export function socialChangeOpen(data) {
  return request({ url: '/api/hr/social-changes', method: 'post', data })
}
export function socialChangeDeclare(id, data) {
  return request({ url: `/api/hr/social-changes/${id}/declare`, method: 'post', data })
}
export function socialChangeVoid(id, note) {
  return request({ url: `/api/hr/social-changes/${id}/void`, method: 'post', data: { note } })
}
