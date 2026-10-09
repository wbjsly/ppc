import request from '@/utils/request'

/** 分页（keyword 税码/政策文号、scope、calcType、lifecycle 计算态筛选） */
export function getTaxPageApi(params) {
  return request.get('/mdm/tax-codes', { params })
}

export function getTaxApi(id) {
  return request.get(`/mdm/tax-codes/${id}`)
}

export function createTaxApi(data) {
  return request.post('/mdm/tax-codes', data)
}

export function updateTaxApi(data) {
  return request.put('/mdm/tax-codes', data)
}

export function deleteTaxApi(id) {
  return request.delete(`/mdm/tax-codes/${id}`)
}

/** 按日期试算（4.14 预铺：命中当时生效记录 / 缺失明示 reasons） */
export function trialTaxApi(params) {
  return request.get('/mdm/tax-codes/trial', { params })
}

export function getTaxVersionsApi(id) {
  return request.get(`/mdm/tax-codes/${id}/versions`)
}

export function getTaxDiffApi(id, from, to) {
  return request.get(`/mdm/tax-codes/${id}/diff`, { params: { from, to } })
}

/** 批量导入（1.6.3） */
export function previewTaxBatchApi(data) {
  return request.post('/mdm/tax-code-batch/preview', data)
}

export function submitTaxBatchApi(data) {
  return request.post('/mdm/tax-code-batch/batch', data)
}
