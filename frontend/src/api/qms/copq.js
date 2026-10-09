import request from '@/utils/request'

/** 质量成本 COPQ（6.7.1~6.7.2，spec quality-cost） */

export function getCopqPageApi(params) {
  return request.get('/qms/copq', { params })
}

/** 手工归集（四类枚举） */
export function createCopqApi(data) {
  return request.post('/qms/copq', data)
}

/** 提交财务确认（底座 CopqFinance，ADMIN 代） */
export function submitFinanceApi(id) {
  return request.post(`/qms/copq/${id}/finance`)
}

/** 异常核实（通过回草稿 / 不予入账作废） */
export function resolveAnomalyApi(id, pass, remark) {
  return request.post(`/qms/copq/${id}/anomaly`, { pass, remark })
}

/** 报表（正式/草稿分列 + 外部失败占比 + 供应商维度） */
export function getReportApi(month) {
  return request.get('/qms/copq/report', { params: { month } })
}
