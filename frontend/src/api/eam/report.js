import request from '@/utils/request'

// 资产报表（7.8.1 变动 / 7.8.2 折旧 / 7.8.3 盘点报告；只读 + CSV 导出）
export const changeEvents = (params) => request.get('/eam/reports/change/events', { params })
export const changeSummary = (params) => request.get('/eam/reports/change/summary', { params })
export const depSummary = (params) => request.get('/eam/reports/depreciation/summary', { params })
export const depDetails = (params) => request.get('/eam/reports/depreciation/details', { params })
export const netLedger = (params) => request.get('/eam/reports/depreciation/ledger', { params })
export const countReports = (params) => request.get('/eam/reports/count/reports', { params })
export const countReportDetail = (planId) => request.get(`/eam/reports/count/reports/${planId}`)

// CSV 导出（BOM+中文表头；超限后端 422 提示缩小范围）
export const exportChangeCsv = (params) =>
  request.get('/eam/reports/export/change', { params, responseType: 'blob' })
export const exportDepreciationCsv = (params) =>
  request.get('/eam/reports/export/depreciation', { params, responseType: 'blob' })
export const exportLedgerCsv = (params) =>
  request.get('/eam/reports/export/ledger', { params, responseType: 'blob' })
