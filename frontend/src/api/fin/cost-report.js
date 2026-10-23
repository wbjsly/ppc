import request from '@/utils/request'

// ================= 财务域成本核算报表（M8-10-3，add-cost-accounting） =================
/** 期间成本报表（summary 汇总卡 + rows 工单明细含超容差红标） */
export const costReport = (params) => request.get('/fin/cost-report', { params })
/** CSV 导出（服务端与报表同源查询，UTF-8 BOM） */
export const costReportCsv = (params) =>
  request.get('/fin/cost-report', { params: { ...params, format: 'csv' }, responseType: 'blob' })
/** 多期趋势与构成（按期间聚合，单期如实单元素） */
export const costTrend = () => request.get('/fin/cost-report/trend')
