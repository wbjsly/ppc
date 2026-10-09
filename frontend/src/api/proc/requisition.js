import request from '@/utils/request'

/** 请购单分页（懒 sweep 在后端查询时执行） */
export function getRequisitionPageApi(params) {
  return request.get('/proc/requisitions', { params })
}

export function getRequisitionDetailApi(id) {
  return request.get(`/proc/requisitions/${id}`)
}

/** 手工创建/编辑/删除（2.1.2） */
export function createManualApi(data) {
  return request.post('/proc/requisitions', data)
}

export function updateManualApi(id, data) {
  return request.put(`/proc/requisitions/${id}`, data)
}

export function deleteManualApi(id) {
  return request.delete(`/proc/requisitions/${id}`)
}

/** MRP 行编辑保存（待确认/已驳回） */
export function updateLinesApi(prId, lines) {
  return request.put(`/proc/requisitions/${prId}/lines`, { lines })
}

/** 整单确认（80% 卡控） */
export function confirmPrApi(id) {
  return request.post(`/proc/requisitions/${id}/confirm`)
}

export function setLineSupplierApi(lineId, supplierId) {
  return request.put(`/proc/requisitions/lines/${lineId}/supplier`, { supplierId })
}

/** 流转询价（APPROVED→PENDING_RFQ） */
export function routePrApi(id) {
  return request.post(`/proc/requisitions/${id}/route`)
}

export function closePrApi(id, reason) {
  return request.post(`/proc/requisitions/${id}/close`, { reason })
}

export function closeLineApi(lineId, reason) {
  return request.post(`/proc/requisitions/lines/${lineId}/close`, { reason })
}

export function saveDeliveryLinesApi(lineId, rows) {
  return request.put(`/proc/requisitions/lines/${lineId}/delivery-lines`, { rows })
}

/** PO 下达回写桩 */
export function allocationApi(lineId, qty) {
  return request.post(`/proc/requisitions/lines/${lineId}/allocation`, { qty })
}

/** 模拟 MRP 净算（2.1.1） */
export function mrpPreviewApi(rows) {
  return request.post('/proc/mrp/preview', { rows })
}

export function mrpGenerateApi(rows) {
  return request.post('/proc/mrp/generate', { rows })
}
