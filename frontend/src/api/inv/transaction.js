import request from '@/utils/request'

/** 出入库流水分页（维度/来源单/类型/方向/时间筛选 + 方向合计，spec stock-posting-engine） */
export function getTransactionsApi(params) {
  return request.get('/inv/transactions', { params })
}

/** 按来源单据下钻（作业台详情抽屉） */
export function getTransactionsByDocApi(bizDocType, bizDocNo) {
  return request.get('/inv/transactions/by-doc', { params: { bizDocType, bizDocNo } })
}
