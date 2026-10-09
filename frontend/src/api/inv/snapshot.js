import request from '@/utils/request'

/** 可用库存查询（四列口径：在手/冻结/预留/可用，spec stock-snapshot） */
export function getSnapshotApi(params) {
  return request.get('/inv/stock-snapshot', { params })
}

/** 预留下钻：SO 单号/行号/锁定时间/数量 */
export function getReservedDetailApi(params) {
  return request.get('/inv/stock-snapshot/reserved-detail', { params })
}

/** 冻结下钻：台账逐条（类型/原因/状态/来源） */
export function getFreezeDetailApi(params) {
  return request.get('/inv/stock-snapshot/freeze-detail', { params })
}

/** 最近一次恒等式校验报告（待核实标记来源） */
export function getCheckReportApi() {
  return request.get('/inv/stock-snapshot/check-report')
}

/** 在制库存查询（当前恒空，工单域接入后自动有数据） */
export function getWipStockApi() {
  return request.get('/inv/wip-stock')
}
