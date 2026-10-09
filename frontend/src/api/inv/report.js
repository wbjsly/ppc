import request from '@/utils/request'

/** 4.14.1 实时库存位行明细（库龄列 + 合计，spec 实时库存位行查询） */
export function getRealtimeApi(params) {
  return request.get('/inv/reports/realtime', { params })
}

/** 4.14.2 期间周转指标（scope: SALES_OUT 默认 / ALL_OUT） */
export function getTurnoverApi(params) {
  return request.get('/inv/reports/turnover', { params })
}

/** 周转汇总（groupBy: ITEM / WAREHOUSE / ABC） */
export function getTurnoverSummaryApi(params) {
  return request.get('/inv/reports/turnover/summary', { params })
}

/** 跨月周转趋势（缺快照月份不返回点，前端 connectNulls:false 断开） */
export function getTurnoverTrendApi(months = 6) {
  return request.get('/inv/reports/turnover/trend', { params: { months } })
}

/** 日结补数（幂等回补最近一个缺失日快照） */
export function backfillDayCloseApi() {
  return request.post('/inv/reports/dayclose/backfill')
}

/** 4.14.3 库龄分桶 + 呆滞占比 */
export function getAgingApi(params) {
  return request.get('/inv/reports/aging', { params })
}

/** 库龄明细（staleOnly=仅呆滞行） */
export function getAgingListApi(params) {
  return request.get('/inv/reports/aging/list', { params })
}

/** 呆滞扫描（日结调度每日自动；手工触发幂等） */
export function scanSlowMovingApi() {
  return request.post('/inv/reports/slow-moving/scan')
}

/**
 * 双路径导出（spec 报表双路径导出）：
 * 同步 CSV（≤EXPORT_ROW_LIMIT）→ 触发浏览器下载；
 * 超限（HTTP 200 + code 422 JSON blob）→ 抛 err.overLimit 由页面转后台任务。
 */
export async function downloadReportApi(type, params) {
  const res = await request.post('/inv/reports/export.csv', null, {
    params: { type, ...params },
    responseType: 'blob',
    timeout: 60000
  })
  if (res && typeof res.type === 'string' && res.type.includes('json')) {
    const j = JSON.parse(await res.text())
    if (j.code !== 200) {
      const err = new Error(j.message || '导出失败')
      err.overLimit = true
      throw err
    }
    return null
  }
  const url = URL.createObjectURL(res)
  const a = document.createElement('a')
  a.href = url
  a.download = `${type}-report-${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(url)
  return true
}

/** 超限后台导出（bi 治理：审批/水印/站内通知，dataset=inv-report） */
export function submitBgExportApi(type, params, estRows = 0) {
  return request.post('/bi/export', {
    dataset: 'inv-report',
    params: { type, ...params, estRows },
    estRows
  })
}
