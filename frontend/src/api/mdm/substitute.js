import request from '@/utils/request'

/** 替代关系列表（direction: source 正向 / target 反查） */
export function getSubstitutesApi(params) {
  return request.get('/mdm/items/substitutes', { params })
}

/** 设置替代（三校验：已发布/非自身/间接环） */
export function setSubstituteApi(id, substituteCode) {
  return request.put(`/mdm/items/${id}/substitute`, null, { params: { substituteCode } })
}

export function clearSubstituteApi(id) {
  return request.delete(`/mdm/items/${id}/substitute`)
}

/** 替代三校验试算（dry-run，表单失焦实时提示） */
export function checkSubstituteApi(itemCode, substituteCode) {
  return request.get('/mdm/items/substitute-check', { params: { itemCode, substituteCode } })
}

/** 替代校验试算（物料表单失焦提示用）：直接复用设置接口的校验语义由后端返回错误 */
export function getItemsOptionsApi() {
  return request.get('/mdm/items/options')
}
